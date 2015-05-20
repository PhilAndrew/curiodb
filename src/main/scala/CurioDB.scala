package curiodb

import akka.actor._
import akka.cluster.Cluster
import akka.dispatch.ControlMessage
import akka.event.LoggingReceive
import akka.io.{IO, Tcp}
import akka.persistence._
import akka.routing.{Broadcast, FromConfig}
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import akka.util.ByteString
import com.dictiography.collections.{IndexedTreeMap, IndexedTreeSet}
import com.typesafe.config.ConfigFactory
import java.net.{InetSocketAddress, URI}
import net.agkn.hll.HLL
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import scala.math.{min, max}
import scala.util.{Success, Failure, Random, Try}

object Commands {

  type CommandSet = mutable.Map[String, mutable.Map[String, String]]
  val commands = mutable.Map[String, CommandSet]()

  ConfigFactory.load("commands.conf").getConfig("commands").entrySet.foreach {entry =>
    val parts = entry.getKey.replace("\"", "").split('.')
    commands.getOrElseUpdate(parts(0), mutable.Map[String, mutable.Map[String, String]]())
    commands(parts(0)).getOrElseUpdate(parts(1), mutable.Map[String, String]())
    commands(parts(0))(parts(1))(parts(2)) = entry.getValue.unwrapped.toString
  }

  def commandSet(command: String): Option[(String, CommandSet)] =
    commands.find(_._2.contains(command))

  def nodeType(command: String): String =
    commandSet(command).map(_._1).getOrElse("")

  def attr(command: String, name: String, default: String): String = commandSet(command).map(_._2) match {
    case Some(set) => set.getOrElse(command, Map[String, String]()).getOrElse(name, default)
    case None => default
  }

  def keyed(command: String): Boolean =
    attr(command, "keyed", "true") != "false"

  def writes(command: String): Boolean =
    attr(command, "writes", "false") == "true" || overwrites(command)

  def overwrites(command: String): Boolean =
    attr(command, "overwrites", "false") == "true"

  def default(command: String, args: Seq[String]): Any =
    attr(command, "default", "none") match {
      case "string" => ""
      case "ok"     => SimpleReply()
      case "error"  => ErrorReply("no such key")
      case "nil"    => null
      case "zero"   => 0
      case "neg1"   => -1
      case "neg2"   => -2
      case "seq"    => Seq()
      case "scan"   => Seq("0", "")
      case "nils"   => args.map(_ => null)
      case "zeros"  => args.map(_ => 0)
      case "none"   => ()
    }

  def argsInRange(command: String, args: Seq[String]): Boolean = {
    val parts = attr(command, "args", "0").split('-')
    val pairs = parts(0) == "pairs"
    if (parts.size == 1 && !pairs) {
      args.size == parts(0).toInt
    } else {
      val start = if (pairs) 2 else parts(0).toInt
      val stop = if (pairs || parts(1) == "many") Int.MaxValue - 1 else parts(1).toInt
      val step = if (pairs) 2 else 1
      (start to stop by step).contains(args.size)
    }
  }

}

case class Payload(input: Seq[Any] = Seq(), db: String = "0", destination: Option[ActorRef] = None) {
  val command = if (input.size > 0) input(0).toString.toLowerCase else ""
  val nodeType = if (command != "") Commands.nodeType(command) else ""
  val key = if (nodeType != "" && input.size > 1 && Commands.keyed(command)) input(1).toString else ""
  val args = input.drop(if (key == "") 1 else 2).map(_.toString)
  lazy val argsPaired = (0 to args.size - 2 by 2).map {i => (args(i), args(i + 1))}
  lazy val argsUpper = args.map(_.toUpperCase)
}

case class Routable(payload: Payload) extends ConsistentHashable {
  override def consistentHashKey: Any = payload.key
}

case class Response(key: String, value: Any)

trait PayloadProcessing extends Actor {

  var payload = Payload()

  type CommandRunner = PartialFunction[String, Any]

  def args: Seq[String] = payload.args

  def argsPaired: Seq[(String, String)] = payload.argsPaired

  def argsUpper: Seq[String] = payload.argsUpper

  def route(
      input: Seq[Any] = Seq(),
      destination: Option[ActorRef] = None,
      clientPayload: Option[Payload] = None,
      broadcast: Boolean = false): Unit = {

    val keys = context.system.actorSelection("/user/keys")
    val p = clientPayload match {
      case Some(payload) => payload
      case None => Payload(input, payload.db, destination)
    }

    if (broadcast) keys ! Broadcast(p) else keys ! Routable(p)

  }

  def respond(response: Any): Unit =
    if (response != ()) {
      payload.destination.foreach {d => d ! Response(payload.key, response)}
    }

  def stop: Unit = context stop self

  def randomItem(iterable: Iterable[String]): String =
    if (iterable.isEmpty) "" else iterable.toSeq(Random.nextInt(iterable.size))

  def randomString(length: Int = 5): String = Random.alphanumeric.take(length).mkString

  def pattern(values: Iterable[String], pattern: String): Iterable[String] = {
    val regex = ("^" + pattern.map {
      case '.'|'('|')'|'+'|'|'|'^'|'$'|'@'|'%'|'\\' => "\\" + _
      case '*' => ".*"
      case '?' => "."
      case c => c
    }.mkString("") + "$").r
    values.filter(regex.pattern.matcher(_).matches)
  }

  def scan(values: Iterable[String]): Seq[String] = {
    val count = if (args.size >= 3) args(2).toInt else 10
    val start = if (args.size >= 1) args(0).toInt else 0
    val end = start + count
    val filtered = if (args.size >= 2) pattern(values, args(1)) else values
    val next = if (end < filtered.size) end else 0
    Seq(next.toString) ++ filtered.slice(start, end)
  }

  def bounds(from: Int, to: Int, size: Int): (Int, Int) =
    (if (from < 0) size + from else from, if (to < 0) size + to else to)

  def slice[T](value: Seq[T]): Seq[T] = {
    val (from, to) = bounds(args(0).toInt, args(1).toInt, value.size)
    value.slice(from, to + 1)
  }

  def aggregate(props: Props): Unit =
    context.actorOf(props, s"aggregate-${payload.command}-${randomString()}") ! payload

}

case class ErrorReply(message: String = "syntax error", prefix: String = "ERR")

case class SimpleReply(message: String = "OK")

case object Persist extends ControlMessage

case object Delete extends ControlMessage

case object Sleep extends ControlMessage

abstract class Node[T] extends PersistentActor with PayloadProcessing with ActorLogging {

  var value: T
  var lastSnapshot: Option[SnapshotMetadata] = None
  var persisting: Boolean = false
  val persistAfter = context.system.settings.config.getInt("curiodb.persist-after")

  def run: CommandRunner

  def persistenceId: String = self.path.name

  def save: Unit = {
    if (persistAfter == 0) {
      saveSnapshot(value)
    } else if (persistAfter > 0 && !persisting) {
      persisting = true
      context.system.scheduler.scheduleOnce(persistAfter milliseconds) {self ! Persist}
    }
  }

  def deleteOldSnapshots(stopping: Boolean = false): Unit =
    if (persistAfter >= 0) {
      lastSnapshot.foreach {meta =>
        val criteria = if (stopping) SnapshotSelectionCriteria()
          else SnapshotSelectionCriteria(meta.sequenceNr, meta.timestamp - 1)
        deleteSnapshots(criteria)
      }
    }

  override def receiveRecover: Receive = {
    case SnapshotOffer(meta, snapshot) =>
      lastSnapshot = Some(meta)
      value = snapshot.asInstanceOf[T]
  }

  def receiveCommand: Receive = {
    case SaveSnapshotSuccess(meta) => lastSnapshot = Some(meta); deleteOldSnapshots()
    case SaveSnapshotFailure(_, e) => log.error(e, "Snapshot write failed")
    case Persist    => persisting = false; saveSnapshot(value)
    case Delete     => deleteOldSnapshots(stopping = true); stop
    case Sleep      => stop
    case p: Payload =>
      payload = p
      respond(Try(run(payload.command)) match {
        case Success(response) => if (Commands.writes(payload.command)) save; response
        case Failure(e) => log.error(e, s"Error running: $payload"); ErrorReply
      })
  }

  override def receive: Receive = LoggingReceive(super.receive)

  def rename(fromValue: Any, toCommand: String): Unit =
    if (payload.key != args(0)) {
      route(Seq("_del", payload.key))
      route(Seq(toCommand, args(0)) ++ (fromValue match {
        case x: Iterable[Any] => x
        case x => Seq(x)
      }))
    }

  def sort(values: Iterable[String]): Any = {
    // TODO: BY/GET support.
    var sorted = if (argsUpper.contains("ALPHA")) values.toSeq.sorted else values.toSeq.sortBy(_.toFloat)
    if (argsUpper.contains("DESC")) sorted = sorted.reverse
    val limit = argsUpper.indexOf("LIMIT")
    if (limit > -1) sorted = sorted.slice(args(limit + 1).toInt, args(limit + 2).toInt)
    val store = argsUpper.indexOf("STORE")
    if (store > -1) {
      route(Seq("_lstore", args(store + 1)) ++ sorted)
      sorted.size
    } else sorted
  }

}

class StringNode extends Node[String] {

  var value = ""

  def valueOrZero: String = if (value == "") "0" else value

  def expire(command: String): Unit = route(Seq(command, payload.key, args(1)))

  def run: CommandRunner = {
    case "_rename"     => rename(value, "set")
    case "get"         => value
    case "set"         => value = args(0); SimpleReply()
    case "setnx"       => run("set"); true
    case "getset"      => val x = value; value = args(0); x
    case "append"      => value += args(0); value
    case "getrange"    => slice(value).mkString
    case "setrange"    => value.patch(args(0).toInt, args(1), 1)
    case "strlen"      => value.size
    case "incr"        => value = (valueOrZero.toInt + 1).toString; value.toInt
    case "incrby"      => value = (valueOrZero.toInt + args(0).toInt).toString; value.toInt
    case "incrbyfloat" => value = (valueOrZero.toFloat + args(0).toFloat).toString; value
    case "decr"        => value = (valueOrZero.toInt - 1).toString; value.toInt
    case "decrby"      => value = (valueOrZero.toInt - args(0).toInt).toString; value.toInt
    case "setex"       => val x = run("set"); expire("expire"); x
    case "psetex"      => val x = run("set"); expire("pexpire"); x
  }

}

class BitmapNode extends Node[mutable.BitSet] {

  var value = mutable.BitSet()

  def last: Int = value.lastOption.getOrElse(0)

  def bitPos: Int = {
    var x = value
    if (args.size > 1) {
      val (from, to) = bounds(args(1).toInt, if (args.size == 3) args(2).toInt else last, last + 1)
      x = x.range(from, to + 1)
    }
    if (args(0) == "1")
      x.headOption.getOrElse(-1)
    else
      (0 to x.lastOption.getOrElse(-1)).collectFirst({
        case i: Int if !x.contains(i) => i
      }).getOrElse(if (args.size > 1 && value.size > 1) -1 else 0)
  }

  def run: CommandRunner = {
    case "_rename"  => rename(value, "_bstore")
    case "_bstore"  => value.clear; value ++= args.map(_.toInt); last / 8 + (if (value.isEmpty) 0 else 1)
    case "_bget"    => value
    case "bitcount" => value.size
    case "getbit"   => value(args(0).toInt)
    case "setbit"   => val x = run("getbit"); value(args(0).toInt) = args(1) == "1"; x
    case "bitpos"   => bitPos
  }

}

class HyperLogLogNode extends Node[HLL] {

  var value = new HLL(
    context.system.settings.config.getInt("curiodb.hyperloglog.register-log"),
    context.system.settings.config.getInt("curiodb.hyperloglog.register-width")
  )

  def add: Int = {
    val x = value.cardinality
    args.foreach {x => value.addRaw(x.hashCode.toLong)}
    if (x == value.cardinality) 0 else 1
  }

  def run: CommandRunner = {
    case "_rename"  => rename(value.toBytes.map(_.toString), "_pfstore")
    case "_pfcount" => value.cardinality.toInt
    case "_pfstore" => value.clear(); value = HLL.fromBytes(args.map(_.toByte).toArray); SimpleReply()
    case "_pfget"   => value
    case "pfadd"    => add
  }

}

class HashNode extends Node[mutable.Map[String, String]] {

  var value = mutable.Map[String, String]()

  def set(arg: Any): String = {val x = arg.toString; value(args(0)) = x; x}

  override def run: CommandRunner = {
    case "_rename"      => rename(run("hgetall"), "_hstore")
    case "_hstore"      => value.clear; run("hmset")
    case "hkeys"        => value.keys
    case "hexists"      => value.contains(args(0))
    case "hscan"        => scan(value.keys)
    case "hget"         => value.getOrElse(args(0), null)
    case "hsetnx"       => if (!value.contains(args(0))) run("hset") else false
    case "hgetall"      => value.flatMap(x => Seq(x._1, x._2))
    case "hvals"        => value.values
    case "hdel"         => val x = run("hexists"); value -= args(0); x
    case "hlen"         => value.size
    case "hmget"        => args.map(value.get(_))
    case "hmset"        => argsPaired.foreach {args => value(args._1) = args._2}; SimpleReply()
    case "hincrby"      => set(value.getOrElse(args(0), "0").toInt + args(1).toInt).toInt
    case "hincrbyfloat" => set(value.getOrElse(args(0), "0").toFloat + args(1).toFloat)
    case "hset"         => val x = !value.contains(args(0)); set(args(1)); x
  }

}

class ListNode extends Node[mutable.ArrayBuffer[String]] {

  var value = mutable.ArrayBuffer[String]()
  var blocked = mutable.LinkedHashSet[Payload]()

  def block: Any = {
    if (value.isEmpty) {
      blocked += payload
      context.system.scheduler.scheduleOnce(args.last.toInt seconds) {
        blocked -= payload
        respond(null)
      }
      ()
    } else run(payload.command.tail)
  }

  def unblock(result: Any): Any = {
    while (value.size > 0 && blocked.size > 0) {
      payload = blocked.head
      blocked -= payload
      respond(run(payload.command.tail))
    }
    result
  }

  def insert: Int = {
    val i = value.indexOf(args(1)) + (if (args(0) == "AFTER") 1 else 0)
    if (i >= 0) {
      value.insert(i, args(2))
      value.size
    } else -1
  }

  def run: CommandRunner = ({
    case "_rename"    => rename(value, "_lstore")
    case "_lstore"    => value.clear; run("rpush")
    case "_sort"      => sort(value)
    case "lpush"      => value ++= args.reverse; run("llen")
    case "rpush"      => value ++= args; run("llen")
    case "lpushx"     => run("lpush")
    case "rpushx"     => run("rpush")
    case "lpop"       => val x = value(0); value -= x; x
    case "rpop"       => val x = value.last; value.reduceToSize(value.size - 1); x
    case "lset"       => value(args(0).toInt) = args(1); SimpleReply()
    case "lindex"     => val x = args(0).toInt; if (x >= 0 && x < value.size) value(x) else null
    case "lrem"       => value.remove(args(0).toInt)
    case "lrange"     => slice(value)
    case "ltrim"      => value = slice(value).asInstanceOf[mutable.ArrayBuffer[String]]; SimpleReply()
    case "llen"       => value.size
    case "blpop"      => block
    case "brpop"      => block
    case "brpoplpush" => block
    case "rpoplpush"  => val x = run("rpop"); route("lpush" +: args :+ x.toString); x
    case "linsert"    => insert
  }: CommandRunner) andThen unblock

}

class SetNode extends Node[mutable.Set[String]] {

  var value = mutable.Set[String]()

  def run: CommandRunner = {
    case "_rename"     => rename(value, "_sstore")
    case "_sstore"     => value.clear; run("sadd")
    case "_sort"       => sort(value)
    case "sadd"        => val x = (args.toSet &~ value).size; value ++= args; x
    case "srem"        => val x = (args.toSet & value).size; value --= args; x
    case "scard"       => value.size
    case "sismember"   => value.contains(args(0))
    case "smembers"    => value
    case "srandmember" => randomItem(value)
    case "spop"        => val x = run("srandmember"); value -= x.toString; x
    case "sscan"       => scan(value)
    case "smove"       => val x = value.remove(args(1)); if (x) {route("sadd" +: args)}; x
  }

}

case class SortedSetEntry(score: Int, key: String = "")(implicit ordering: Ordering[(Int, String)])
    extends Ordered[SortedSetEntry] {
  def compare(that: SortedSetEntry): Int =
    ordering.compare((this.score, this.key), (that.score, that.key))
}

class SortedSetNode extends Node[(IndexedTreeMap[String, Int], IndexedTreeSet[SortedSetEntry])] {

  var value = (new IndexedTreeMap[String, Int](), new IndexedTreeSet[SortedSetEntry]())

  def keys: IndexedTreeMap[String, Int] = value._1

  def scores: IndexedTreeSet[SortedSetEntry] = value._2

  def add(score: Int, key: String): Boolean = {
    val exists = remove(key)
    keys.put(key, score)
    scores.add(SortedSetEntry(score, key))
    !exists
  }

  def remove(key: String): Boolean = {
    val exists = keys.containsKey(key)
    if (exists) {
      scores.remove(SortedSetEntry(keys.get(key), key))
      keys.remove(key)
    }
    exists
  }

  def increment(key: String, by: Int): Int = {
    val score = (if (keys.containsKey(key)) keys.get(key) else 0) + by
    remove(key)
    add(score, key)
    score
  }

  def rank(key: String, reverse: Boolean = false): Int = {
    val index = scores.entryIndex(SortedSetEntry(keys.get(key), key))
    if (reverse) keys.size - index else index
  }

  def range(from: SortedSetEntry, to: SortedSetEntry, reverse: Boolean): Seq[String] = {
    if (from.score > to.score) return Seq()
    var result = scores.subSet(from, true, to, true).toSeq
    result = limit[SortedSetEntry](if (reverse) result.reverse else result)
    if (argsUpper.contains("WITHSCORES"))
      result.flatMap(x => Seq(x.key, x.score.toString))
    else
      result.map(_.key)
  }

  def rangeByIndex(from: String, to: String, reverse: Boolean = false): Seq[String] = {
    var (fromIndex, toIndex) = bounds(from.toInt, to.toInt, keys.size)
    if (reverse) {
      fromIndex = keys.size - fromIndex - 1
      toIndex = keys.size - toIndex - 1
    }
    range(scores.exact(fromIndex), scores.exact(toIndex), reverse)
  }

  def rangeByScore(from: String, to: String, reverse: Boolean = false): Seq[String] = {
    def parse(arg: String, dir: Int) = arg match {
      case "-inf" => if (scores.isEmpty) 0 else scores.first().score
      case "+inf" => if (scores.isEmpty) 0 else scores.last().score
      case arg if arg.startsWith("(") => arg.toInt + dir
      case _ => arg.toInt
    }
    range(SortedSetEntry(parse(from, 1)), SortedSetEntry(parse(to, -1) + 1), reverse)
  }

  def rangeByKey(from: String, to: String, reverse: Boolean = false): Seq[String] = {
    def parse(arg: String) = arg match {
      case "-" => ""
      case "+" => if (keys.size == 0) "" else keys.lastKey() + "x"
      case arg if "[(".indexOf(arg.head) > -1 => arg.tail
    }
    val (fromKey, toKey) = (parse(from), parse(to))
    if (fromKey > toKey) return Seq()
    val result = keys.subMap(fromKey, from.head == '[', toKey, to.head == '[').toSeq
    limit[(String, Int)](if (reverse) result.reverse else result).map(_._1)
  }

  def limit[T](values: Seq[T]): Seq[T] = {
    val i = argsUpper.indexOf("LIMIT")
    if (i > 1)
      values.slice(args(i + 1).toInt, args(i + 1).toInt + args(i + 2).toInt)
    else
      values
  }

  def run: CommandRunner = {
    case "_rename"          => rename(scores.flatMap(x => Seq(x.score, x.key)), "_zstore")
    case "_zstore"          => keys.clear; scores.clear; run("zadd")
    case "_zget"            => keys
    case "_sort"            => sort(keys.keys)
    case "zadd"             => argsPaired.map(arg => add(arg._1.toInt, arg._2)).filter(x => x).size
    case "zcard"            => keys.size
    case "zcount"           => rangeByScore(args(0), args(1)).size
    case "zincrby"          => increment(args(0), args(1).toInt)
    case "zlexcount"        => rangeByKey(args(0), args(1)).size
    case "zrange"           => rangeByIndex(args(0), args(1))
    case "zrangebylex"      => rangeByKey(args(0), args(1))
    case "zrangebyscore"    => rangeByScore(args(0), args(1))
    case "zrank"            => rank(args(0))
    case "zrem"             => remove(args(0))
    case "zremrangebylex"   => rangeByKey(args(0), args(1)).map(remove).filter(x => x).size
    case "zremrangebyrank"  => rangeByIndex(args(0), args(1)).map(remove).filter(x => x).size
    case "zremrangebyscore" => rangeByScore(args(0), args(1)).map(remove).filter(x => x).size
    case "zrevrange"        => rangeByIndex(args(1), args(0), reverse = true)
    case "zrevrangebylex"   => rangeByKey(args(1), args(0), reverse = true)
    case "zrevrangebyscore" => rangeByScore(args(1), args(0), reverse = true)
    case "zrevrank"         => rank(args(0), reverse = true)
    case "zscan"            => scan(keys.keys)
    case "zscore"           => keys.get(args(0))
  }

}

case class PubSubEvent(event: String, channelOrPattern: String)

trait PubSubServer extends PayloadProcessing {

  val channels = mutable.Map[String, mutable.Set[ActorRef]]()
  val patterns = mutable.Map[String, mutable.Set[ActorRef]]()

  def subscribeOrUnsubscribe: Unit = {
    val pattern = payload.command.startsWith("_p")
    val current = if (pattern) patterns else channels
    val key = if (pattern) args(0) else payload.key
    val subscriber = payload.destination.get
    val subscribing = payload.command.drop(if (pattern) 2 else 1) == "subscribe"
    val updated = if (subscribing)
      current.getOrElseUpdate(key, mutable.Set[ActorRef]()).add(subscriber)
    else
      !current.get(key).filter(_.remove(subscriber)).isEmpty
    if (!subscribing && updated && current(key).isEmpty) current -= key
    if (updated) subscriber ! PubSubEvent(payload.command.tail, key)
  }

  def publish: Int = {
    channels.get(payload.key).map({subscribers =>
      val message = Response(payload.key, Seq("message", payload.key, args(0)))
      subscribers.foreach(_ ! message)
      subscribers.size
    }).sum + patterns.filterKeys(!pattern(Seq(payload.key), _).isEmpty).map({entry =>
      val message = Response(payload.key, Seq("pmessage", entry._1, payload.key, args(0)))
      entry._2.foreach(_ ! message)
      entry._2.size
    }).sum
  }

  def runPubSub: CommandRunner = {
    case "_numsub"       => channels.get(payload.key).map(_.size).sum
    case "_numpat"       => patterns.values.map(_.size).sum
    case "_channels"     => pattern(channels.keys, args(0))
    case "_subscribe"    => subscribeOrUnsubscribe
    case "_unsubscribe"  => subscribeOrUnsubscribe
    case "_psubscribe"   => subscribeOrUnsubscribe
    case "_punsubscribe" => subscribeOrUnsubscribe
    case "publish"       => publish
  }

}

@SerialVersionUID(1L)
class NodeEntry(
    val nodeType: String,
    @transient var node: Option[ActorRef] = None,
    @transient var expiry: Option[(Long, Cancellable)] = None,
    @transient var sleep: Option[Cancellable] = None)
  extends Serializable

class KeyNode extends Node[mutable.Map[String, mutable.Map[String, NodeEntry]]] with PubSubServer {

  type DB = mutable.Map[String, NodeEntry]
  var value = mutable.Map[String, DB]()
  val wrongType = ErrorReply("Operation against a key holding the wrong kind of value", prefix = "WRONGTYPE")
  val sleepAfter = context.system.settings.config.getInt("curiodb.sleep-after")
  val sleepEnabled = sleepAfter > 0

  def dbFor(name: String): DB =
    value.getOrElseUpdate(name, mutable.Map[String, NodeEntry]())

  def db: DB = dbFor(payload.db)

  def persist: Int = db(payload.key).expiry match {
    case Some((_, cancellable)) =>
      cancellable.cancel()
      db(payload.key).expiry = None
      1
    case None => 0
  }

  def expire(when: Long): Int = {
    persist
    val expires = (when - System.currentTimeMillis).toInt milliseconds
    val cancellable = context.system.scheduler.scheduleOnce(expires) {
      self ! Payload(Seq("_del", payload.key), db = payload.db)
    }
    db(payload.key).expiry = Some((when, cancellable))
    1
  }

  def sleep: Unit = {
    val when = sleepAfter milliseconds
    val key = payload.key
    val entry = db(key)
    entry.sleep.foreach(_.cancel())
    entry.sleep = Some(context.system.scheduler.scheduleOnce(when) {
      db.get(key).foreach {entry =>
        entry.node.foreach(_ ! Sleep)
        entry.node = None
      }
    })
  }

  def ttl: Long = db(payload.key).expiry match {
    case Some((when, _)) => when - System.currentTimeMillis
    case None => -1
  }

  def sort: Any = db(payload.key).nodeType match {
    case "list" | "set" | "sortedset" =>
      val sortArgs = Seq("_sort", payload.key) ++ payload.args
      node ! Payload(sortArgs, db = payload.db, destination = payload.destination)
    case _ => wrongType
  }

  def validate: Option[Any] = {
    val exists      = db.contains(payload.key)
    val nodeType    = if (exists) db(payload.key).nodeType else ""
    val invalidType = (nodeType != "" && payload.nodeType != nodeType &&
      payload.nodeType != "keys" && !Commands.overwrites(payload.command))
    val cantExist   = payload.command == "lpushx" || payload.command == "rpushx"
    val mustExist   = payload.command == "setnx"
    val default     = Commands.default(payload.command, payload.args)
    if (invalidType)
      Some(wrongType)
    else if ((exists && cantExist) || (!exists && mustExist))
      Some(0)
    else if (!exists && default != ())
      Some(default)
    else
      None
  }

  def node: ActorRef = {
    if (payload.nodeType == "keys")
      self
    else
      db.get(payload.key).flatMap(_.node) match {
        case Some(node) => node
        case None => create(payload.db, payload.key, payload.nodeType).get
      }
  }

  def create(db: String, key: String, nodeType: String, recovery: Boolean = false): Option[ActorRef] = {
    val node = if (recovery && sleepEnabled) None else Some(context.actorOf(nodeType match {
      case "string"      => Props[StringNode]
      case "bitmap"      => Props[BitmapNode]
      case "hyperloglog" => Props[HyperLogLogNode]
      case "hash"        => Props[HashNode]
      case "list"        => Props[ListNode]
      case "set"         => Props[SetNode]
      case "sortedset"   => Props[SortedSetNode]
    }, s"$db-$nodeType-$key"))
    dbFor(db)(key) = new NodeEntry(nodeType, node)
    if (!recovery) save
    node
  }

  def delete(key: String, dbName: Option[String] = None): Boolean =
    dbFor(dbName.getOrElse(payload.db)).remove(key) match {
      case Some(entry) => entry.node.foreach(_ ! Delete); true
      case None => false
    }

  override def run: CommandRunner = ({
    case "_del"          => (payload.key +: args).map(key => delete(key))
    case "_keys"         => pattern(db.keys, args(0))
    case "_randomkey"    => randomItem(db.keys)
    case "_flushdb"      => db.keys.map(key => delete(key)); SimpleReply()
    case "_flushall"     => value.foreach(db => db._2.keys.map(key => delete(key, Some(db._1)))); SimpleReply()
    case "exists"        => args.map(db.contains)
    case "ttl"           => ttl / 1000
    case "pttl"          => ttl
    case "expire"        => expire(System.currentTimeMillis + (args(0).toInt * 1000))
    case "pexpire"       => expire(System.currentTimeMillis + args(0).toInt)
    case "expireat"      => expire(args(0).toLong / 1000)
    case "pexpireat"     => expire(args(0).toLong)
    case "type"          => if (db.contains(payload.key)) db(payload.key).nodeType else null
    case "renamenx"      => val x = db.contains(payload.key); if (x) {run("rename")}; x
    case "rename"        => db(payload.key).node.foreach(_ ! Payload(Seq("_rename", payload.key, args(0)), db = payload.db)); SimpleReply()
    case "persist"       => persist
    case "sort"          => sort
  }: CommandRunner) orElse runPubSub

  override def receiveCommand: Receive = ({
    case Routable(p) => payload = p; validate match {
      case Some(errorOrDefault) => respond(errorOrDefault)
      case None =>
        val overwrite = !db.get(payload.key).filter(_.nodeType != payload.nodeType).isEmpty
        if (Commands.overwrites(payload.command) && overwrite) delete(payload.key)
        node ! payload
        if (payload.command match {
          case "_subscribe" | "_unsubscribe" | "publish" => false
          case _ => sleepEnabled
        }) sleep
    }
  }: Receive) orElse super.receiveCommand

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, snapshot) =>
      snapshot.asInstanceOf[mutable.Map[String, DB]].foreach {db =>
        db._2.foreach {item => create(db._1, item._1, item._2.nodeType, recovery = true)}
      }
  }

}

trait AggregateCommands extends PayloadProcessing {

  def runAggregate: CommandRunner = {
    case "mset"         => argsPaired.foreach {args => route(Seq("set", args._1, args._2))}; SimpleReply()
    case "msetnx"       => aggregate(Props[AggregateMSetNX])
    case "mget"         => aggregate(Props[AggregateMGet])
    case "bitop"        => aggregate(Props[AggregateBitOp])
    case "dbsize"       => aggregate(Props[AggregateDBSize])
    case "del"          => aggregate(Props[AggregateDel])
    case "keys"         => aggregate(Props[AggregateKeys])
    case "flushdb"      => aggregate(Props[AggregateFlushDB])
    case "flushall"     => aggregate(Props[AggregateFlushAll])
    case "pfcount"      => aggregate(Props[AggregateHyperLogLogCount])
    case "pfmerge"      => aggregate(Props[AggregateHyperLogLogMerge])
    case "randomkey"    => aggregate(Props[AggregateRandomKey])
    case "scan"         => aggregate(Props[AggregateScan])
    case "sdiff"        => aggregate(Props[AggregateSet])
    case "sinter"       => aggregate(Props[AggregateSet])
    case "sunion"       => aggregate(Props[AggregateSet])
    case "sdiffstore"   => aggregate(Props[AggregateSetStore])
    case "sinterstore"  => aggregate(Props[AggregateSetStore])
    case "sunionstore"  => aggregate(Props[AggregateSetStore])
    case "zinterstore"  => aggregate(Props[AggregateSortedSetStore])
    case "zunionstore"  => aggregate(Props[AggregateSortedSetStore])
  }

}

trait PubSubClient extends PayloadProcessing {

  var channels = mutable.Set[String]()
  var patterns = mutable.Set[String]()

  def subscribeOrUnsubscribe: Unit = {
    val pattern = payload.command.head == 'p'
    val subscribed = if (pattern) patterns else channels
    val xs = if (args.isEmpty) subscribed.toSeq else args
    xs.foreach {x => route(Seq("_" + payload.command, x), destination = payload.destination, broadcast = pattern)}
  }

  override def stop: Unit = {
    channels.foreach {x => route(Seq("_unsubscribe", x), destination = Some(self))}
    patterns.foreach {x => route(Seq("_punsubscribe", x), destination = Some(self), broadcast = true)}
    println("pubsub stop")
    super.stop
  }

  def runPubSub: CommandRunner = {
    case "subscribe"    => subscribeOrUnsubscribe
    case "unsubscribe"  => subscribeOrUnsubscribe
    case "psubscribe"   => subscribeOrUnsubscribe
    case "punsubscribe" => subscribeOrUnsubscribe
    case "pubsub"       => args(0) match {
      case "channels" => aggregate(Props[AggregatePubSubChannels])
      case "numsub"   => aggregate(Props[AggregatePubSubNumSub])
      case "numpat"   => route(Seq("_numpat", randomString()), destination = payload.destination)
    }
  }

  def receivePubSub: Receive = {
    case PubSubEvent(event, channelOrPattern) =>
      val current = if (event.head == 'p') patterns else channels
      val subscribing = event.stripPrefix("p") == "subscribe"
      val subscribed = subscribing && current.add(channelOrPattern)
      val unsubscribed = !subscribing && current.remove(channelOrPattern)
      if (subscribed || unsubscribed) {
        self ! Response(channelOrPattern, Seq(event, channelOrPattern, current.size.toString))
      }
  }

}

class ClientNode extends Node[Null] with PubSubClient with AggregateCommands {

  var value = null
  var quitting = false
  val buffer = new StringBuilder()
  var client: Option[ActorRef] = None
  var db = payload.db
  val end = "\r\n"

  def run: CommandRunner = ({
    case "select"       => db = args(0); SimpleReply()
    case "echo"         => args(0)
    case "ping"         => SimpleReply("PONG")
    case "time"         => val x = System.nanoTime; Seq(x / 1000000000, x % 1000000)
    case "shutdown"     => context.system.terminate(); SimpleReply()
    case "quit"         => quitting = true; SimpleReply()
  }: CommandRunner) orElse runPubSub orElse runAggregate

  def validate: Option[ErrorReply] = {
    if (payload.nodeType == "")
      Some(ErrorReply(s"unknown command '${payload.command}'"))
    else if ((payload.key == "" && Commands.keyed(payload.command))
        || !Commands.argsInRange(payload.command, payload.args))
      Some(ErrorReply(s"wrong number of arguments for '${payload.command}' command"))
    else
      None
  }

  def parseInput: Seq[String] = {

    var pos = 0

    def next(length: Int = 0): String = {
      val to = if (length <= 0) buffer.indexOf(end, pos) else pos + length
      val part = buffer.slice(pos, to)
      if (part.size != to - pos) throw new Exception()
      pos = to + end.size
      part.stripLineEnd
    }

    def parts: Seq[String] = {
      val part = next()
      part.head match {
        case '-'|'+'|':' => Seq(part.tail)
        case '$'         => Seq(next(part.tail.toInt))
        case '*'         => (1 to part.tail.toInt).map(_ => parts.head)
        case _           => part.split(' ')
      }
    }

    Try(parts) match {
      case Success(output) => buffer.delete(0, pos); output
      case Failure(_)      => Seq[String]()
    }

  }

  def writeResponse(response: Any): String = response match {
    case x: Iterable[Any]        => s"*${x.size}${end}${x.map(writeResponse).mkString}"
    case x: Boolean              => writeResponse(if (x) 1 else 0)
    case x: Number               => s":$x$end"
    case ErrorReply(msg, prefix) => s"-$prefix $msg$end"
    case SimpleReply(msg)        => s"+$msg$end"
    case null                    => s"$$-1$end"
    case x                       => s"$$${x.toString.size}$end$x$end"
  }

  override def receiveCommand: Receive = ({

    case Tcp.Received(data) =>
      var input = Seq[String]()
      buffer.append(data.utf8String)
      while ({input = parseInput; input.size > 0}) {
        payload = Payload(input, db = db, destination = Some(self))
        client = Some(sender())
        validate match {
          case Some(error) => respond(error)
          case None =>
            if (payload.nodeType == "client")
              self ! payload
            else
              route(clientPayload = Option(payload))
        }
      }

    case Tcp.PeerClosed => stop

    case Response(_, response) =>
      client.get ! Tcp.Write(ByteString(writeResponse(response)))
      if (quitting) self ! Delete

  }: Receive) orElse receivePubSub orElse super.receiveCommand

}

abstract class Aggregate[T](val command: String) extends Actor with PayloadProcessing with ActorLogging {

  var responses = mutable.Map[String, T]()

  def keys: Seq[String] = args

  def ordered: Seq[T] = keys.map(responses(_))

  def complete: Any = ordered

  def begin = keys.foreach {key => route(Seq(command, key), destination = Some(self))}

  def receive: Receive = LoggingReceive {
    case p: Payload => payload = p; begin
    case Response(key, value) =>
      val keyOrIndex = if (responses.contains(key)) (responses.size + 1).toString else key
      responses(keyOrIndex) = value.asInstanceOf[T]
      if (responses.size == keys.size) {
        respond(Try(complete) match {
          case Success(response) => response
          case Failure(e) => log.error(e, s"Error running: $payload"); ErrorReply
        })
        stop
      }
  }

}

class AggregateMGet extends Aggregate[String]("get")

abstract class AggregateSetReducer[T](command: String) extends Aggregate[T](command) {
  type S = mutable.Set[String]
  lazy val reducer: (S, S) => S = payload.command.tail match {
    case x if x.startsWith("diff")  => (_ &~ _)
    case x if x.startsWith("inter") => (_ & _)
    case x if x.startsWith("union") => (_ | _)
  }
}

class BaseAggregateSet extends AggregateSetReducer[mutable.Set[String]]("smembers")

class AggregateSet extends BaseAggregateSet {
  override def complete: Any = ordered.reduce(reducer)
}

class AggregateSetStore extends BaseAggregateSet {
  override def complete: Unit =
    route(Seq("_sstore", payload.key) ++ ordered.reduce(reducer), destination = payload.destination)
}

class AggregateSortedSetStore extends AggregateSetReducer[IndexedTreeMap[String, Int]]("_zget") {

  lazy val aggregatePos = argsUpper.indexOf("AGGREGATE")
  lazy val aggregateName = if (aggregatePos == -1) "SUM" else argsUpper(aggregatePos + 1)
  lazy val weightPos = argsUpper.indexOf("WEIGHTS")
  lazy val aggregate: (Int, Int) => Int = aggregateName match {
    case "SUM" => (_ + _)
    case "MIN" => min _
    case "MAX" => max _
  }

  def weight(i: Int): Int = if (weightPos == -1) 1 else args(weightPos + i + 1).toInt

  override def keys: Seq[String] = args.slice(1, args(0).toInt + 1)

  override def complete: Unit = {
    var i = 0
    val result = ordered.reduce({(x, y) =>
      val out = new IndexedTreeMap[String, Int]()
      reducer(x.keySet, y.keySet).foreach {key =>
        lazy val xVal = x.get(key) * (if (i == 0) weight(i) else 1)
        lazy val yVal = y.get(key) * weight(i + 1)
        val value = if (!y.containsKey(key)) xVal
          else if (!x.containsKey(key)) yVal
          else aggregate(xVal, yVal)
        out.put(key, value)
      }
      i += 1
      out
    }).entrySet.toSeq.flatMap(e => Seq(e.getValue.toString, e.getKey))
    route(Seq("_zstore", payload.key) ++ result, destination = payload.destination)
  }

}

class AggregateBitOp extends Aggregate[mutable.BitSet]("_bget") {
  override def keys: Seq[String] = args.drop(2)
  override def complete: Unit = {
    val result = args(0).toUpperCase match {
      case "AND" => ordered.reduce(_ & _)
      case "OR"  => ordered.reduce(_ | _)
      case "XOR" => ordered.reduce(_ ^ _)
      case "NOT" =>
        val from = ordered(0).headOption.getOrElse(1) - 1
        val to = ordered(0).lastOption.getOrElse(1) - 1
        mutable.BitSet(from until to: _*) ^ ordered(0)
    }
    route(Seq("_bstore", args(1)) ++ result, destination = payload.destination)
  }
}

class AggregateHyperLogLogCount extends Aggregate[Long]("_pfcount") {
  override def complete: Long = responses.values.sum
}

class AggregateHyperLogLogMerge extends Aggregate[HLL]("_pfget") {
  override def complete: Unit = {
    val result = ordered.reduce({(x, y) => x.union(y); x}).toBytes.map(_.toString)
    route(Seq("_pfstore", payload.key) ++ result, destination = payload.destination)
  }
}

abstract class AggregateBroadcast[T](command: String) extends Aggregate[T](command) {
  def broadcastArgs: Seq[String] = payload.args
  override def keys: Seq[String] = (1 to context.system.settings.config.getInt("curiodb.keynodes")).map(_.toString)
  override def begin: Unit = route(command +: broadcastArgs, destination = Some(self), broadcast = true)
}

class AggregatePubSubChannels extends AggregateBroadcast[Iterable[String]]("_channels") {
  override def broadcastArgs: Seq[String] = Seq(if (args.size == 2) args(1) else "*")
  override def complete: Iterable[String] = responses.values.reduce(_ ++ _)
}

class AggregatePubSubNumSub extends Aggregate[Int]("_numsub") {
  override def keys: Seq[String] = args.drop(1)
  override def begin: Unit = if (keys.isEmpty) {respond(Seq()); stop} else super.begin
  override def complete: Seq[String] = keys.flatMap(x => Seq(x, responses(x).toString))
}

abstract class BaseAggregateKeys extends AggregateBroadcast[Iterable[String]]("_keys") {
  def reduced: Iterable[String] = responses.values.reduce(_ ++ _)
}

class AggregateKeys extends BaseAggregateKeys {
  override def complete: Iterable[String] = reduced
}

class AggregateScan extends BaseAggregateKeys {
  override def broadcastArgs: Seq[String] = Seq("*")
  override def complete: Seq[String] = scan(reduced)
}

class AggregateDBSize extends BaseAggregateKeys {
  override def broadcastArgs: Seq[String] = Seq("*")
  override def complete: Int = reduced.size
}

class AggregateRandomKey extends AggregateBroadcast[String]("_randomkey") {
  override def complete: String = randomItem(responses.values.filter(_ != ""))
}

abstract class AggregateBool(command: String) extends AggregateBroadcast[Iterable[Boolean]](command) {
  def trues: Iterable[Boolean] = responses.values.flatten.filter(_ == true)
}

class AggregateDel extends AggregateBool("_del") {
  override def broadcastArgs: Seq[String] = payload.args
  override def complete: Int = trues.size
}

class AggregateMSetNX extends AggregateBool("exists") {
  override def keys: Seq[String] = payload.argsPaired.map(_._1)
  override def complete: Boolean = {
    if (trues.isEmpty) payload.argsPaired.foreach {args => route(Seq("set", args._1, args._2))}
    trues.isEmpty
  }
}

abstract class AggregateSimpleReply(command: String) extends AggregateBroadcast[String](command) {
  override def complete: SimpleReply = SimpleReply()
}

class AggregateFlushDB extends AggregateSimpleReply("_flushdb")

class AggregateFlushAll extends AggregateSimpleReply("_flushall")

class Server(listen: URI) extends Actor {
  IO(Tcp)(context.system) ! Tcp.Bind(self, new InetSocketAddress(listen.getHost, listen.getPort))
  def receive: Receive = LoggingReceive {
    case Tcp.Connected(_, _) => sender() ! Tcp.Register(context.actorOf(Props[ClientNode]))
  }
}

object CurioDB {
  def main(args: Array[String]): Unit = {

    val sysName   = "curiodb"
    val config    = ConfigFactory.load()
    val listen    = new URI(config.getString("curiodb.listen"))
    val node      = if (args.isEmpty) config.getString("curiodb.node") else args(0)
    val nodes     = config.getObject("curiodb.nodes").map(n => (n._1 -> new URI(n._2.unwrapped.toString)))
    val keyNodes  = nodes.size * config.getInt("akka.actor.deployment./keys.cluster.max-nr-of-instances-per-node")
    val seedNodes = nodes.values.map(u => s""" "akka.${u.getScheme}://${sysName}@${u.getHost}:${u.getPort}" """)

    val system = ActorSystem(sysName, ConfigFactory.parseString(s"""
      curiodb.keynodes = ${keyNodes}
      curiodb.node = ${node}
      akka.cluster.seed-nodes = [${seedNodes.mkString(",")}]
      akka.cluster.min-nr-of-members = ${nodes.size}
      akka.remote.netty.tcp.hostname = "${nodes(node).getHost}"
      akka.remote.netty.tcp.port = ${nodes(node).getPort}
      akka.actor.deployment./keys.nr-of-instances = ${keyNodes}
    """).withFallback(config))

    Cluster(system).registerOnMemberUp {
      println("All nodes are up!")
      system.actorOf(Props[KeyNode].withRouter(FromConfig()), name = "keys")
    }

    system.actorOf(Props(new Server(listen)), "server")
    Await.result(system.whenTerminated, Duration.Inf)

  }
}
