package sbt
package server

/**
 * This is a strategy which can cancel tasks when their associated cancellation `Future[_]`
 * is completed.
 */
class ServerTaskCancellation(serverState: ServerState) extends TaskCancellationStrategy {
  /* override */ class State(canceller: RunningTaskEngine) {
    @volatile
    var enabled = true
    def cancel(): Unit = {
      if (enabled) canceller.cancelAndShutdown()
    }
  }
  override def onTaskEngineStart(canceller: RunningTaskEngine): State = {
    val state = new State(canceller)
    serverState.lastCommand match {
      case Some(command) =>
        command.command.cancelStatus.onCancel { () =>
          state.cancel()
        }
      case None => // TODO - This is probably an error, but we'll ignore it for now, just in case
      // This is hooked *before* the server is initialized.
    }
    state
  }
  override def onTaskEngineFinish(state: State): Unit = {
    state.enabled = false
  }
}
object ServerTaskCancellation {
  def getShims(): Seq[Setting[_]] = {
    Seq(
      Keys.taskCancelStrategy in Global := { (state: State) =>
        val sstate = server.ServerState.extract(state)
        new ServerTaskCancellation(sstate)
      })

  }
}