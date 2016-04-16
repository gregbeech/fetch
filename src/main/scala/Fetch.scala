package fetch

import cats.{ MonadError, ~>, Eval }
import cats.data.{ State, StateT }
import cats.std.option._
import cats.std.list._
import cats.syntax.cartesian._
import cats.syntax.traverse._
import cats.free.{ Free }

trait DataSource[I, A, M[_]] {
  def identity: String = this.toString
  def fetchMany(ids: List[I]): M[Map[I, A]]
}

trait DataSourceCache

trait Cache[T <: DataSourceCache]{
  def makeKey[M[_]](k: Any, ds: DataSource[_, _, M]): (String, Any) =
    (ds.identity, k)
  def update[I, A](c: T, k: (String, I), v: A): T
  def get[I](c: T, k: (String, I)): Option[Any]
}

trait Env[C <: DataSourceCache, I]{
  def cache: C
  def ids: List[I]
  def rounds: List[Round]

  def next(
    newCache: C,
    newRounds: List[Round],
    newIds: List[I]
  ): Env[C, I]
}

case class Round(ds: String, kind: RoundKind) // todo: time info

sealed abstract class RoundKind
case class OneRound(id: Any) extends RoundKind
case class ManyRound(ids: List[Any]) extends RoundKind

case class FetchEnv[C <: DataSourceCache, I](
  cache: C,
  ids: List[I] = Nil,
  rounds: List[Round] = Nil
) extends Env[C, I]{

  def next(
    newCache: C,
    newRounds: List[Round],
    newIds: List[I]
  ): FetchEnv[C, I] =
    copy(cache = newCache, rounds = newRounds, ids = newIds)
}

case class FetchFailure[C <: DataSourceCache, I](env: Env[C, I])(
  implicit CC: Cache[C]
) extends Throwable

object algebra {
  sealed abstract class FetchOp[A] extends Product with Serializable
  final case class FetchOne[I, A, M[_]](a: I, ds: DataSource[I, A, M]) extends FetchOp[A]
  final case class FetchMany[I, A, M[_]](as: List[I], ds: DataSource[I, A, M]) extends FetchOp[List[A]]
  final case class Result[A](a: A) extends FetchOp[A]
  final case class FetchError[A, E <: Throwable](err: E)() extends FetchOp[A]
}


object types {
  import algebra.FetchOp

  type Fetch[A] = Free[FetchOp, A]

  type FetchInterpreter[M[_], E <: Env[_, _]] = {
    type f[x] = StateT[M, E, x]
  }
}

object cache {
  // no cache
  case class NoCache() extends DataSourceCache

  implicit object NoCacheImpl extends Cache[NoCache]{
    override def get[I](c: NoCache, k: (String, I)): Option[Any] = None
    override def update[I, A](c: NoCache, k: (String, I), v: A): NoCache = c
  }

  // in-memory cache
  case class InMemoryCache(state:Map[Any, Any]) extends DataSourceCache

  object InMemoryCache {
    def empty: InMemoryCache = InMemoryCache(Map.empty[Any, Any])

    def apply(results: (Any, Any)*): InMemoryCache =
      InMemoryCache(results.foldLeft(Map.empty[Any, Any])({
        case (c, (k, v)) => c.updated(k, v)
      }))
  }

  implicit object InMemoryCacheImpl extends Cache[InMemoryCache]{
    override def get[I](c: InMemoryCache, k: (String, I)): Option[Any] = c.state.get(k)
    override def update[I, A](c: InMemoryCache, k: (String, I), v: A): InMemoryCache = InMemoryCache(c.state.updated(k, v))
  }
}

object Fetch {
  import algebra._
  import types._
  import cache._
  import interpreters._

  def pure[A](a: A): Fetch[A] =
    Free.liftF(Result(a))

  def error[A](e: Throwable): Fetch[A] =
    Free.liftF(FetchError(e))

  def apply[I, A, M[_]](i: I)(
    implicit DS: DataSource[I, A, M]
  ): Fetch[A] =
    Free.liftF(FetchOne[I, A, M](i, DS))

  def collect[I, A, M[_]](ids: List[I])(
    implicit DS: DataSource[I, A, M]
  ): Fetch[List[A]] =
    Free.liftF(FetchMany[I, A, M](ids, DS))

  /// xxx: List[B] -> (B -> Fetch[A]) -> Fetch[List[A]]
  def traverse[I, A, B, M[_]](ids: List[B])(f: B => I)(
    implicit DS: DataSource[I, A, M]
  ): Fetch[List[A]] =
    collect(ids.map(f))

  def coalesce[I, A, M[_]](fl: I, fr: I)(
    implicit
      DS: DataSource[I, A, M]
  ): Fetch[(A, A)] =
    Free.liftF(FetchMany[I, A, M](List(fl, fr), DS)).map(l => (l(0), l(1)))

  def join[I, R, A, B, M[_]](fl: I, fr: R)(
    implicit
      DS: DataSource[I, A, M],
    DSS: DataSource[R, B, M]
  ): Fetch[(A, B)] =
    (Fetch(fl) |@| Fetch(fr)).tupled

  def runFetch[I, A, C <: DataSourceCache, M[_]](
    fa: Fetch[A],
    env: FetchEnv[C, I]
  )(
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): M[A] = fa.foldMap[FetchInterpreter[M, FetchEnv[C, I]]#f](interpreter).runA(env)

  def runEnv[I, A, C <: DataSourceCache, M[_]](
    fa: Fetch[A],
    env: FetchEnv[C, I]
  )(
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): M[FetchEnv[C, I]] = fa.foldMap[FetchInterpreter[M, FetchEnv[C, I]]#f](interpreter).runS(env)

  def run[I, A, M[_]](
    fa: Fetch[A]
  )(
    implicit
      MM: MonadError[M, Throwable]
  ): M[A] = runFetch[I, A, NoCache, M](fa, FetchEnv(NoCache()))(MM, NoCacheImpl)

  def runCached[I, A, C <: DataSourceCache, M[_]](
    fa: Fetch[A],
    cache: C
  )(
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): M[A] = runFetch[I, A, C, M](fa, FetchEnv(cache))(MM, CC)

  def runWith[I, A, M[_]](
    fa: Fetch[A],
    i: FetchOp ~> M
  )(
    implicit
    MM: MonadError[M, Throwable]
  ): M[A] = fa foldMap i
}

object interpreters {
  import algebra._
  import types._
  import cache._

  def interpreter[C <: DataSourceCache, I, E <: Env[C, I], M[_]](
    implicit
      MM: MonadError[M, Throwable],
    CC: Cache[C]
  ): FetchOp ~> FetchInterpreter[M, FetchEnv[C, I]]#f = {
    new (FetchOp ~> FetchInterpreter[M, FetchEnv[C, I]]#f) {
      def apply[A](fa: FetchOp[A]): FetchInterpreter[M, FetchEnv[C, I]]#f[A] = {
        StateT[M, FetchEnv[C, I], A] { env: FetchEnv[C, I] => fa match {
          case Result(a) => MM.pure((env, a))
          case FetchError(e) => MM.raiseError(e)
          case FetchOne(id: I, ds) => {
            val cache = env.cache
            val round = Round(ds.identity, OneRound(id))
            val newRounds = env.rounds ++ List(round)
            CC.get(cache, CC.makeKey[Any](id, ds)).fold[M[(FetchEnv[C, I], A)]](
              MM.flatMap(ds.fetchMany(List(id)).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
                res.get(id).fold[M[(FetchEnv[C, I], A)]](
                  MM.raiseError(
                    FetchFailure(
                      env.next(cache, newRounds, List(id))
                    )
                  )
                )(result => {
                  val newCache = CC.update(cache, CC.makeKey[Any](id, ds), result)
                  MM.pure((env.next(newCache, newRounds, List(id)), result))
                })
              })
            )(cached => {
              MM.pure((env.next(cache, newRounds, List(id)), cached.asInstanceOf[A]))
            })
          }
          case FetchMany(ids: List[I], ds) => {
            val cache = env.cache
            val round = Round(ds.identity, ManyRound(ids))
            val newRounds = env.rounds ++ List(round)
            val newIds = ids.distinct.filterNot(i => CC.get(cache, CC.makeKey[Any](i, ds)).isDefined)
            if (newIds.isEmpty)
              MM.pure((env.next(cache, newRounds, newIds), ids.flatMap(id => CC.get(cache, CC.makeKey[Any](id, ds)))))
            else {
              MM.flatMap(ds.fetchMany(newIds).asInstanceOf[M[Map[I, A]]])((res: Map[I, A]) => {
                ids.map(res.get(_)).sequence.fold[M[(FetchEnv[C, I], A)]](
                  MM.raiseError(
                    FetchFailure(
                      env.next(cache, newRounds, newIds)
                    )
                  )
                )(results => {
                  val newCache = res.foldLeft(cache)({
                    case (c, (k, v)) => CC.update(c, CC.makeKey[Any](k, ds), v)
                  })
                  MM.pure((env.next(newCache, newRounds, newIds), results))
                })
              })
            }
          }
        }
        }
      }
    }
  }
}
