package com.twitter.finagle.server

import com.twitter.conversions.time._
import com.twitter.finagle.{ClientConnection, Failure, ListeningServer, Service, ServiceFactory}
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.param.{Label, ProtocolLibrary}
import com.twitter.finagle.transport.{Transport, TransportContext}
import com.twitter.util.{Closable, Future, Return, Throw, Time}
import com.twitter.util.registry.GlobalRegistry
import java.net.SocketAddress

/**
 * A standard template implementation for [[com.twitter.finagle.server.StackServer]]
 * that uses the transport + dispatcher pattern.
 *
 * @see The [[https://twitter.github.io/finagle/guide/Servers.html user guide]]
 *      for further details on Finagle servers and their configuration.
 * @see [[StackServer]] for a generic representation of a stack server.
 * @see [[StackServer.newStack]] for the default modules used by Finagle
 *      servers.
 */
trait StdStackServer[Req, Rep, This <: StdStackServer[Req, Rep, This]]
  extends ListeningStackServer[Req, Rep, This] { self: This =>

  /**
   * The type we write into the transport.
   */
  protected type In

  /**
   * The type we read out of the transport.
   */
  protected type Out

  /**
   * The type of the transport's context.
   */
  protected type Context <: TransportContext

  /**
   * Defines a typed [[com.twitter.finagle.server.Listener]] for this server.
   * Concrete StackServer implementations are expected to specify this.
   */
  protected def newListener(): Listener[In, Out, Context]

  /**
   * Defines a dispatcher, a function which binds a transport to a
   * [[com.twitter.finagle.Service]]. Together with a `Listener`, it
   * forms the foundation of a finagle server. Concrete implementations
   * are expected to specify this.
   *
   * @see [[com.twitter.finagle.dispatch.GenSerialServerDispatcher]]
   */
  protected def newDispatcher(transport: Transport[In, Out] {
    type Context <: self.Context
  }, service: Service[Req, Rep]): Closable

  final protected def newListeningServer(
    serviceFactory: ServiceFactory[Req, Rep],
    addr: SocketAddress
  )(trackSession: ClientConnection => Unit): ListeningServer = {

    // Listen over `addr` and serve traffic from incoming transports to
    // `serviceFactory` via `newDispatcher`.
    val listener = newListener()

    // Export info about the listener type so that we can query info
    // about its implementation at runtime. This assumes that the `toString`
    // of the implementation is sufficiently descriptive.
    val listenerImplKey = Seq(
      ServerRegistry.registryName,
      params[ProtocolLibrary].name,
      params[Label].label,
      "Listener"
    )
    GlobalRegistry.get.put(listenerImplKey, listener.toString)

    listener.listen(addr) { transport =>
      val clientConnection = new ClientConnectionImpl(transport)
      val futureService = transport.peerCertificate match {
        case None => serviceFactory(clientConnection)
        case Some(cert) =>
          Contexts.local.let(Transport.peerCertCtx, cert) {
            serviceFactory(clientConnection)
          }
      }
      futureService.respond {
        case Return(service) =>
          val d = newDispatcher(transport, service)
          // Now that we have a dispatcher, we have a higher notion of what `close(..)` does, so use it
          clientConnection.setClosable(d)
          trackSession(clientConnection)

        case Throw(exc) =>
          // If we fail to create a new session locally, we continue establishing
          // the session but (1) reject any incoming requests; (2) close it right
          // away. This allows protocols that support graceful shutdown to
          // also gracefully deny new sessions.
          val d = newDispatcher(
            transport,
            Service.const(
              Future.exception(Failure.rejected("Terminating session and ignoring request", exc))
            )
          )

          // Now that we have a dispatcher, we have a higher notion of what `close(..)` does, so use it
          clientConnection.setClosable(d)
          trackSession(clientConnection)
          // We give it a generous amount of time to shut down the session to
          // improve our chances of being able to do so gracefully.
          d.close(10.seconds)
      }
    }
  }

  private class ClientConnectionImpl(t: Transport[In, Out] {
    type Context <: self.Context
  }) extends ClientConnection {
    @volatile
    private var closable: Closable = t

    def setClosable(closable: Closable): Unit = {
      this.closable = closable
    }

    override def remoteAddress: SocketAddress = t.remoteAddress
    override def localAddress: SocketAddress = t.localAddress
    // In the Transport + Dispatcher model, the Transport is a source of truth for
    // the `onClose` future: closing the dispatcher will result in closing the
    // Transport and closing the Transport will trigger shutdown of the dispatcher.
    // Therefore, even when we swap the closable that is the target of `this.close(..)`,
    // they both will complete the transports `onClose` future.
    override val onClose: Future[Unit] = t.onClose.unit
    override def close(deadline: Time): Future[Unit] = closable.close(deadline)
  }
}
