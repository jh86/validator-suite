package org.w3.util

import java.util.concurrent.TimeoutException
import akka.dispatch.Await
import akka.dispatch.ExecutionContext
import akka.dispatch.Future
import akka.dispatch.Promise
import akka.util.Duration
import scalaz.Validation._
import scalaz.Scalaz._
import scalaz._
import org.w3.util.Pimps._

object FutureVal {
  
  def apply[S](body: => S)(implicit context: ExecutionContext): FutureVal[Throwable, S]  = {
    new FutureVal(Future(fromTryCatch(body)))
  }
  
  def pure[S](body: => S)(implicit context: ExecutionContext): FutureVal[Throwable, S] = {
    new FutureVal(Promise.successful(fromTryCatch(body)))
  }
  
  def successful[F, S](success: S)(implicit context: ExecutionContext, 
      onTimeout: TimeoutException => F): FutureVal[F, S] = {
    new FutureVal(Promise.successful(Success(success)))
  }
  
  def failed[F, S](failure: F)(implicit context: ExecutionContext,
      onTimeout: TimeoutException => F): FutureVal[F, S] = {
    new FutureVal(Promise.successful(Failure(failure)))
  }
  
  def fromValidation[F, S](validation: Validation[F, S])(implicit context: ExecutionContext,
      onTimeout: TimeoutException => F): FutureVal[F, S] = {
    new FutureVal(Promise.successful(validation))
  }
  
  def applyTo[S](future: Future[S])(implicit context: ExecutionContext): FutureVal[Throwable, S] = {
    new FutureVal(future.map[Validation[Throwable, S]] { value =>
        Success(value)
      } recover { case t: Throwable =>
        Failure(t)
      })
  }
  
  def sequence[S](iterable: Iterable[Future[S]])(
      implicit context: ExecutionContext): FutureVal[Throwable, Iterable[S]] = {
    FutureVal.applyTo(Future.sequence(iterable))
  }
  
}

class FutureVal[+F, +S] private (
    private val future: Future[Validation[F, S]])(
    implicit val timeout: Function1[TimeoutException, F], context: ExecutionContext) {
  
  def asFuture: Future[Validation[F, S]] = future
  
  def isCompleted: Boolean = future.isCompleted
  
  def map[R](success: S => R): FutureVal[F, R] = 
    pureFold(f => f, success)
  
  def mapFail[T](failure: F => T): FutureVal[T, S] = 
    pureFold(failure, s => s)
  
  def failMap[T](failure: F => T): FutureVal[T, S] = mapFail(failure)
  
  def failWith[T](failure: T)(implicit onTimeout: TimeoutException => T): FutureVal[T, S] = {
    new FutureVal(future.map {
      case Failure(_) => Failure(failure)
      case Success(success) => Success(success)
    })
  }
  
  def pureFold[T, R](f1: F => T, f2: S => R): FutureVal[T, R] = {
    new FutureVal(future.map {
      case Failure(failure) => Failure(f1(failure))
      case Success(success) => Success(f2(success))
    })(t => f1(timeout(t)), context)
  }
  
  def flatMap[T >: F, R](success: S => FutureVal[T, R]): FutureVal[T, R] = {
    new FutureVal(future.flatMap {
      case Failure(failure_) => Promise.successful(Failure(failure_))
      case Success(success_) => success(success_).asFuture
    })
  }
  
  // TODO
  def flatFold[T, R](failure: F => FutureVal[T, R], success: S => FutureVal[T, R])(
      implicit onTimeout: TimeoutException => T): FutureVal[T, R] = {
    new FutureVal(future.flatMap {
      case Failure(failure_) => failure(failure_).asFuture
      case Success(success_) => success(success_).asFuture
    })
  }
  
  def foreach(f: S => Unit): Unit = future foreach { _ foreach f }
  
  def value: Option[Validation[F, S]] = {
    future.value.fold (
      either => either.fold(
          _ => sys.error("The inner future of a FutureVal cannot have a value of Left[Throwable]."),
          vali => Some(vali)
        ),
      None
    )
    /* i.e:
    future.value match {
      case Some(Right(vali)) => Some(vali)
      case Some(Left(t)) => sys.error("The inner future of a FutureVal cannot have a value of Left[Throwable].")
      case None => None
    }
    */
  }
  
  def await(atMost: Duration): Option[Validation[F, S]] = {
    try {
      Some(Await.result(future, atMost))
    } catch {
      case timeout: TimeoutException => None
    }
  }
  
  def result: Validation[F, S] = {
    value.fold(
      s => s,
      Failure(timeout(new TimeoutException()))
    )
  }
  
  def result(atMost: Duration): Validation[F, S] = {
    try {
      Await.result(future, atMost)
    } catch {
      case e: TimeoutException => Failure(timeout(e))
    }
  }
  
  def waitAnd(atMost: Duration, f: Function1[FutureVal[F, S], _] = {_: FutureVal[F, S] => ()}): FutureVal[F, S] = {
    try {
      Await.result(future, atMost)
      f(this)
      this
    } catch {
      case e => { f(this); this } 
    }
  }
  
  def readyIn(atMost: Duration): FutureVal[F, S] = {
    FutureVal.fromValidation(
      try {
        Await.result(future, atMost)
      } catch {
        case e: TimeoutException => Failure(timeout(e))
      }
    )
  }
  
  def onTimeout[T >: F](onTimeout: TimeoutException => T): FutureVal[T, S] = { 
    new FutureVal[T, S](future)(onTimeout, context)
  }
  
  def onSuccess(pf: PartialFunction[S, _]): FutureVal[F, S] =
    onComplete{case Success(s) => pf(s)}
  
  def onFailure(pf: PartialFunction[F, _]): FutureVal[F, S] =
    onComplete{case Failure(f) => pf(f)}
  
  def onComplete(pf: PartialFunction[Validation[F, S], _]): FutureVal[F, S] = {
    future.onSuccess(pf)
    this
  }
  
  def toVSPromise[R](implicit evF: F <:< R, evS: S <:< R): VSPromise[R] =
    VSPromise.applyTo(this.asInstanceOf[FutureVal[R, R]])
  
  def toVSPromise[R](onTimeout: TimeoutException => R)(implicit evF: F <:< R, evS: S <:< R): VSPromise[R] =
    VSPromise.applyTo(this.asInstanceOf[FutureVal[R, R]].onTimeout(onTimeout))
  
}

