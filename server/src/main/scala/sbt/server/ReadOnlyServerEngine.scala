package sbt
package server

import java.util.Queue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import sbt.protocol._

// a little wrapper around protocol.request to keep the client/serial with it
case class ServerRequest(client: LiveClient, serial: Long, request: protocol.Request)

/**
 * This class represents an event loop which sits outside the normal sbt command
 * processing loop.
 *
 * This event loop can handle ServerState requests which are read-only against build state
 * or affect only the server state.   All other requests (mostly just ExecutionRequest) are added into a command queue
 * which the Command processing loop is expected to consume.
 *
 * @param queue - The Queue from which we read server requests and handle them.
 * @param nextStateRef - The atomic reference we use to communicate the current build state between ourselves and the
 *                       full server engine.
 *
 */
class ReadOnlyServerEngine(
  queue: BlockingQueue[ServerRequest],
  nextStateRef: AtomicReference[State]) extends Thread("read-only-sbt-event-loop") {
  // TODO - We should have a log somewhere to store events.
  private var serverStateRef = new AtomicReference[ServerState](ServerState())
  def serverState = serverStateRef.get()
  private val running = new AtomicBoolean(true)
  // TODO - We should probably limit the number of deferred client requests so we don't explode during startup to DoS attacks...
  private val deferredStartupBuffer = collection.mutable.ArrayBuffer.empty[ServerRequest]

  // TODO - This only works because it is called from a single thread.
  private def updateState(f: ServerState => ServerState): Unit =
    serverStateRef.lazySet(f(serverStateRef.get))

  /**
   * Object we use to synch work between the read-only "fast" event loop and
   *  the ServerEngine.
   *
   *  This is an ugly synchronized animal living between two threads.  There are
   *  two method called from the ReadOnlySide:
   *     - enqueueWork:  Push a new client request into the queue, joining with existing requests
   *     - cancelRequest: Attempt to cancel a request in the queue, or notify non-blocking to the ServerEngine.
   *  And one method called from the ServerEngine side
   *     - blockAndTakeNext: Block the current thread until some work is ready to run, and give me the latest state.
   *
   */
  final object engineWorkQueue extends ServerEngineQueue {

    private var nextExecutionId: Long = 1L // 1 is first, because 0 is invalid flag.
    private def getAndIncrementExecutionId: Long = {
      val prev = nextExecutionId
      nextExecutionId += 1
      prev
    }
    private var workQueue: List[ServerEngineWork] = Nil
    private var cancelStore: Map[Long, WorkCancellationStatus] = Map.empty
    /**
     * Called by the ServerEngine thread.  This should block that thread
     *  until work is ready, then notify that the work is starting and
     *  grab the current serverState.
     */
    override def blockAndTakeNext: (ServerState, ServerEngineWork) =
      synchronized {
        // Block until we have work ready
        def blockUntilWork(): ServerEngineWork =
          workQueue match {
            case hd :: tail =>
              workQueue = tail
              hd
            case _ =>
              // Here we need to block until we have more work
              this.wait() // Note: waiting on this, means the queue has more data.
              blockUntilWork()
          }
        val work = blockUntilWork()
        val state = serverState
        state.eventListeners.send(protocol.ExecutionStarting(work.id.id))
        (state, work)
      }

    /**
     * Called from the ReadOnlyEngine thread.
     *
     *  This is responsible for minimizing work on the way in,
     *  handling cancellations, etc.
     */
    def enqueueWork(work: ServerRequest): Unit =
      synchronized {
        // Check to see if something similar is on the queue
        // -  If so, merge the two together
        // -  If not:
        //       Notify current listeners that work is pending
        //       Add to the queue
        work match {
          case ServerRequest(client, serial, command: ExecutionRequest) =>
            val (work, isNew) =
              workQueue find {
                case old: CommandExecutionWork => old.command == command.command
                case _ => false
              } match {
                case Some(previous: CommandExecutionWork) =>
                  previous.withNewRequester(client) -> false
                case None =>
                  // Make a new work item
                  val id = ExecutionId(getAndIncrementExecutionId)
                  // Register cancellation notifications.
                  val cancel = WorkCancellationStatus()
                  cancelStore += (id.id -> cancel)
                  // Create work item
                  val newItem = CommandExecutionWork(id, command.command, Set(client), cancel)
                  newItem -> true
              }
            // Always notify the current client of his work
            if (serial != 0L) {
              import sbt.protocol.executionReceivedFormat
              client.reply(serial, ExecutionRequestReceived(id = work.id.id))
              if (isNew) {
                // If this is a new item in the queue, tell all clients about it.
                serverState.eventListeners.send(protocol.ExecutionWaiting(work.id.id, work.command))
              }
            }
            // Now, we insert the work either at the end, or where it belongs.
            def insertWork(remaining: List[ServerEngineWork]): List[ServerEngineWork] =
              remaining match {
                case hd :: tail if hd.id == work.id => work :: tail
                case hd :: tail => hd :: insertWork(remaining)
                case Nil => work :: Nil
              }
            workQueue = insertWork(workQueue)
            // Here we should notify the server about the new work!
            notify()
          case wtf =>
            // TODO - Server/Logic error
            throw new Error(s"Square peg in round hole!  Is not a workRequest item: $wtf")
        }
      }
    def cancelRequest(id: Long): Boolean =
      synchronized {
        // Find out if we have a work item with the given id.
        // - If so, mark it as starting/failed in two events, and remove it. return true
        // - If not, try to cancel something in the store
        // - otherwise, fail.
        val found = workQueue collect {
          case old: CommandExecutionWork if old.id.id == id => old
        }
        found match {
          case item :: Nil =>
            // Remove the item from the queue
            workQueue = workQueue filterNot (_.id.id == id)
            // Tell everyone that this request is dead.
            serverState.eventListeners.send(protocol.ExecutionStarting(id))
            serverState.eventListeners.send(protocol.ExecutionFailure(id))
            // mark it as cancelled (this removes it from our store)
            removeCancelStore(id)
            item.cancelStatus.cancel()

          case _ =>
            cancelStore get id match {
              case Some(value) =>
                removeCancelStore(id)
                value.cancel()
              case _ => false
            }
        }
      }

    private def removeCancelStore(id: Long): Unit =
      cancelStore -= id
  }

  override def run() {
    while (running.get && nextStateRef.get == null) {
      // here we poll, on timeout we check to see if we have build state yet.
      // We give at least one second for loading the build before timing out.
      queue.poll(1, java.util.concurrent.TimeUnit.SECONDS) match {
        case null => () // Ignore.
        case ServerRequest(client, serial, request) =>
          handleRequestsNoBuildState(client, serial, request)
      }
    }
    // Now we flush through all the events we had.
    // TODO - handle failures 
    if (running.get) {
      for {
        ServerRequest(client, serial, request) <- deferredStartupBuffer
      } handleRequestsWithBuildState(client, serial, request, nextStateRef.get)
    }
    // Now we just run with the initialized build.
    while (running.get) {
      // Here we can block on requests, because we have cached
      // build state and no longer have to see if the sbt command
      // loop is started.p
      val ServerRequest(client, serial, request) = queue.take
      // TODO - handle failures 
      try handleRequestsWithBuildState(client, serial, request, nextStateRef.get)
      catch {
        case e: Exception =>
          // TODO - Fatal exceptions?
          client.reply(serial, protocol.ErrorResponse(e.getMessage))
      }
    }
  }

  private def syntheticExecuteRequest(client: LiveClient, serial: Long, scopedKey: sbt.ScopedKey[_], buildState: State): Unit = {
    handleRequestsWithBuildState(client, serial, ExecutionRequest(Def.showFullKey(scopedKey)), buildState)
  }
  private def handleRequestsNoBuildState(client: LiveClient, serial: Long, request: Request): Unit =
    request match {
      case ListenToEvents() =>
        // We do not send a listening message, because we aren't yet.
        updateState(_.addEventListener(client))
        client.reply(serial, ReceivedResponse())
      case ClientClosedRequest() =>
        updateState(_.disconnect(client))
        client.reply(serial, ReceivedResponse())
      case _ =>
        // Defer all other messages....
        deferredStartupBuffer.append(ServerRequest(client, serial, request))
    }
  private def handleRequestsWithBuildState(client: LiveClient, serial: Long, request: Request, buildState: State): Unit =
    request match {
      case KillServerRequest() =>
        // TODO - Is killing completely the right thing?
        System.exit(0)
      case ListenToEvents() =>
        updateState(_.addEventListener(client))
        client.reply(serial, ReceivedResponse())
      case UnlistenToEvents() =>
        updateState(_.removeEventListener(client))
        client.reply(serial, ReceivedResponse())
      case ListenToBuildChange() =>
        updateState(_.addBuildListener(client))
        client.reply(serial, ReceivedResponse())
      case UnlistenToBuildChange() =>
        updateState(_.removeBuildListener(client))
        client.reply(serial, ReceivedResponse())
      case SendSyntheticBuildChanged() =>
        BuildStructureCache.sendBuildStructure(client, SbtDiscovery.buildStructure(buildState))
      case ClientClosedRequest() =>
        updateState(_.disconnect(client))
        client.reply(serial, ReceivedResponse())
      case KeyLookupRequest(key) =>
        val parser: complete.Parser[Seq[sbt.ScopedKey[_]]] = Act.aggregatedKeyParser(buildState)
        import SbtToProtocolUtils.scopedKeyToProtocol
        complete.Parser.parse(key, parser) match {
          case Right(sk) => client.reply(serial, KeyLookupResponse(key, sk.map(k => scopedKeyToProtocol(k))))
          case Left(msg) => client.reply(serial, KeyLookupResponse(key, Seq.empty))
        }
      case ListenToValue(key) =>
        SbtToProtocolUtils.protocolToScopedKey(key, buildState) match {
          case Some(scopedKey) =>
            val extracted = Project.extract(buildState)

            if (SettingCompletions.isSetting(scopedKey.key)) {
              // get the value of the setting key from the build state, and send it to the client
              val settingKey = SettingKey(scopedKey.key.asInstanceOf[sbt.AttributeKey[Any]]) in scopedKey.scope
              val change = SbtToProtocolUtils.settingKeyToProtocolValue(settingKey, extracted)
              client.send(ValueChanged(key, change))

              // register the key listener.
              // TODO: needs support somewhere to send events when the value of setting keys are updated
              updateState(_.addKeyListener(client, scopedKey))
            } else {
              // Schedule the key to run as well as registering the key listener.
              updateState(_.addKeyListener(client, scopedKey))
            }
            client.reply(serial, ReceivedResponse())

          case None => // Issue a no such key error
            client.reply(serial, KeyNotFound(key))
        }
      case UnlistenToValue(key) =>
        SbtToProtocolUtils.protocolToScopedKey(key, buildState) match {
          case Some(scopedKey) =>
            updateState(_.removeKeyListener(client, scopedKey))
            client.reply(serial, ReceivedResponse())
          case None => // Issue a no such key error
            client.reply(serial, KeyNotFound(key))
        }
      case SendSyntheticValueChanged(key) =>
        SbtToProtocolUtils.protocolToScopedKey(key, buildState) match {
          case Some(scopedKey) =>
            val extracted = Project.extract(buildState)

            if (SettingCompletions.isSetting(scopedKey.key)) {
              // get the value of the setting key from the build state, and send it to the client
              val settingKey = SettingKey(scopedKey.key.asInstanceOf[sbt.AttributeKey[Any]]) in scopedKey.scope
              val change = SbtToProtocolUtils.settingKeyToProtocolValue(settingKey, extracted)
              client.send(ValueChanged(key, change))
            } else {
              // for tasks, we have to run the task to generate the change event.
              // we set serial=0 because we don't want to generate a reply for the
              // synthetic ExecutionRequest generated in this call.

              // FIXME TODO BROKEN this should guarantee a ValueChanged EVEN IF the
              // value has not really changed, and should send it ONLY to this
              // client requesting a synthetic change if it hasn't actually changed.
              // Right now this will generate a ValueChanged even on no change only
              // because we are broken and always send it when we run the task...
              // but if we fix that, then this won't be guaranteed to send an event
              // at all.
              syntheticExecuteRequest(client, serial = 0L, scopedKey, buildState)
            }
            client.reply(serial, ReceivedResponse())

          case None => // Issue a no such key error
            client.reply(serial, KeyNotFound(key))
        }
      case req: ExecutionRequest =>
        // TODO - Handle "queue is full" issues.
        engineWorkQueue.enqueueWork(ServerRequest(client, serial, request))
      case CancelExecutionRequest(id) =>
        client.reply(serial, CancelExecutionResponse(engineWorkQueue.cancelRequest(id)))
      // don't client.reply to ExecutionRequest here - it's done in the work queue
      case keyRequest: KeyExecutionRequest =>
        // translate to a regular ExecutionRequest
        SbtToProtocolUtils.protocolToScopedKey(keyRequest.key, buildState) match {
          case Some(scopedKey) =>
            syntheticExecuteRequest(client, serial, scopedKey, buildState)
          case None =>
            client.reply(serial, KeyNotFound(keyRequest.key))
        }
      case CommandCompletionsRequest(line, level) =>
        val combined = buildState.combinedParser
        val completions = complete.Parser.completions(combined, line, level)
        def convertCompletion(c: complete.Completion): protocol.Completion =
          protocol.Completion(
            c.append,
            c.display,
            c.isEmpty)
        client.reply(serial, CommandCompletionsResponse(completions.get map convertCompletion))
      case _: ConfirmRequest | _: ReadLineRequest =>
        client.reply(serial, ErrorResponse(s"Request ${request.getClass.getName} is intended to go from server to client"))
      case _: RegisterClientRequest =>
        client.reply(serial, ErrorResponse("Client can only be registered once, on connection"))
    }

}
