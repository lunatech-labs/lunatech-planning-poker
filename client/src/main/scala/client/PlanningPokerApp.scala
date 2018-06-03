package client

import cats.effect.IO
import client.view._
import com.github.lavrov.poker._
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import outwatch.{Handler, Sink}
import outwatch.dom.VNode
import outwatch.dom.dsl._
import outwatch.http.Http
import outwatch.util.WebSocket

object PlanningPokerApp {
  case class AppState(
      user: Option[Participant],
      session: Option[CurrentPlanningSession]
  )

  case class CurrentPlanningSession(
      id: String,
      planningSession: Option[PlanningSession]
  )

  sealed trait Action
  object Action {
    case class Login(name: String) extends Action
    case class RequestSession() extends Action
    case class ReceiveSession(sessionId: String) extends Action
    case class SendPlanningSessionAction(action: PlanningSession.Action) extends Action
    case class UpdatePlanningSession(session: PlanningSession) extends Action
    case object Noop extends Action
  }

  type Store = outwatch.util.Store[AppState, Action]
  def Store(initState: AppState): IO[(Observable[AppState], Sink[Action])] =
    for {
      handler <- Handler.create[Action]
      store = outwatch.util.Store(initState, reducer, handler)
    }
    yield {
      var currentSub: Option[Sub.WebSocket] = None
      val newSource =
      store.source.mapEval { state =>
        def close(sub: Sub.WebSocket): Unit = {
          currentSub = None
          WebSocketIO.closeAndRemove(sub.url)
        }
        def connect(sub: Sub.WebSocket) = {
          currentSub = Some(sub)
          (handler <-- WebSocketIO.getOrElseCreate(sub.url).source.map { m =>
            println(s"WS: $m")
            sub.actionFn(m.data.toString)
          }).unsafeRunSync()
        }
        IO {
          (currentSub, subscriptions(state)) match {
            case (None, None) => ()
            case (Some(s), None) =>
              close(s)
            case (None, Some(s)) =>
              connect(s)
            case (Some(current), Some(updated)) =>
              if (current.url == updated.url) () // the same subscrbtion -- do nothing
              else {
                close(current)
                connect(updated)
              }
          }
          state
        }
      }
      (newSource, store.sink)
    }

  trait Sub
  object Sub {
    case class WebSocket(url: String, actionFn: String => Action) extends Sub
  }

  object WebSocketIO {
    val sockets: scala.collection.mutable.Map[String, WebSocket] = scala.collection.mutable.Map.empty
    def send(url: String, message: String): IO[Action] = IO {
      getOrElseCreate(url).ws.send(message)
      Action.Noop
    }
    def getOrElseCreate(url: String): WebSocket = sockets.getOrElseUpdate(url, WebSocket(url))
    def closeAndRemove(url: String): Unit = {
      sockets.get(url).foreach { ws =>
        ws.ws.close()
        sockets.remove(url)
      }
    }
  }

  def reducer(state: AppState, action: Action): (AppState, Option[IO[Action]]) = action match {
    case Action.RequestSession() =>
      state -> Some {
        val request = Http.Request("http://localhost:8080/session")
        Http.single(request, Http.Post)
          .attempt
          .map {
            case Right(response) =>
              if (response.status == 200) {
                println("Received " + response.body)
                Action.ReceiveSession(io.circe.parser.decode[String](response.body.toString).right.get)
              }
              else {
                println(s"Bad response ${response.status}")
                Action.Noop
              }
            case Left(error) =>
              println(s"Error while creating session. $error")
              Action.Noop
          }
      }
    case Action.ReceiveSession(sessionId) =>
      state.copy(session = Some(CurrentPlanningSession(sessionId, None))) -> None
    case Action.Login(userName) =>
      // TODO: request
      state.copy(user = Some(Participant(userName, userName))) -> None
    case Action.UpdatePlanningSession(session) =>
      state.copy(session = state.session.map(_.copy(planningSession = Some(session)))) -> None
    case Action.SendPlanningSessionAction(psAction) =>
      state -> state.session.map(_.id).map { sessionId =>
        import io.circe.syntax._, io.circe.generic.auto._
        WebSocketIO.send(s"ws://localhost:8080/session/ws/$sessionId", psAction.asJson.noSpaces)
      }
    case Action.Noop =>
      state -> None
  }

  def subscriptions(state: AppState): Option[Sub.WebSocket] = {
    import io.circe.parser.decode, io.circe.generic.auto._
    state.session.map(s =>
      Sub.WebSocket(
        s"ws://localhost:8080/session/ws/${s.id}",
        msg =>
          Action.UpdatePlanningSession(decode[PlanningSession](msg).right.get)
      )
    )
  }

  def view(state: AppState, sink: Sink[Action]): VNode = {
    def sessionJoinSink(state: AppState): Option[Sink[Unit]] =
      state.session.flatMap(_.planningSession).zip(state.user).headOption // if user and session are defined
        .collect {
          case (session, user) if !session.participants.contains(user) =>
            sink.redirectMap(_ =>
              PlanningPokerApp.Action.SendPlanningSessionAction(
                PlanningSession.Action.AddParticipant(user)))
        }
    div(
      UserView.render(
        state.user,
        sessionJoinSink(state),
        sink.redirectMap(PlanningPokerApp.Action.Login)
      ),
      state.session match {
        case Some(session) =>
          session.planningSession match {
            case Some(planningSession) =>
              PlanningSessionView.render(planningSession, state.user, sink)
            case None =>
              div("Connecting...")
          }
        case None =>
          button(
            "Start session",
            onClick(Action.RequestSession()) --> sink
          )
      },
      state.session match {
        case Some(session) => div("Session id: ", session.id)
        case None => div()
      }
    )
  }
}
