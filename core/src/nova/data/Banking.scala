package nova.data

import forge.tags._
import forge.implicits.collections._

import nova.core._
import nova.lang.{LocalMem, Math}
import nova.utils._

/**
  * Abstract class for any banking strategy
  */
sealed abstract class Banking {
  def nBanks: Int
  def stride: Int
  def dims: Seq[Int]
  @api def bankSelect[I:IntLike](addr: Seq[I]): I
}

/**
  * Banking address function (alpha*A / B) mod N
  */
case class ModBanking(N: Int, B: Int, alpha: Seq[Int], dims: Seq[Int]) extends Banking {
  override def nBanks: Int = N
  override def stride: Int = B

  @api def bankSelect[I:IntLike](addr: Seq[I]): I = {
    import nova.utils.IntLike._
    (Math.meta.sum(alpha.zip(addr).map{case (a,i) => a*i }) / B) % N
  }
}
object ModBanking {
  def Unit(rank: Int) = ModBanking(1, 1, Seq.fill(rank)(1), Seq.fill(rank)(1))
}


/**
  * Helper structure for holding metadata for buffer ports
  * The mux port is the time multiplexed slot the associated access has in reference to all other accesses
  * on the same port occurring at the same time.
  *
  * The buffer port is the port at which the associated access should be connected for N-buffers.
  * If the buffer is depth 1, this is always Some(0).
  * If the access occurs outside of the metapipeline that uses this buffer, the port will be None.
  */
case class Port(muxPort: Int, bufferPort: Option[Int])


/**
  * Used during memory analysis to track intermediate results
  */
case class Instance(
  reads:    Set[Set[AccessMatrix]], // All reads within this group
  writes:   Set[Set[AccessMatrix]], // All writes within this group
  ctrls:    Set[Ctrl],              // Set of controllers these accesses are in
  metapipe: Option[Ctrl],           // Controller if at least some accesses require n-buffering
  banking:  Seq[Banking],           // Banking information
  depth:    Int,                    // Depth of n-buffer
  cost:     Int,                    // Cost estimate of this configuration
  ports:    Map[AccessMatrix,Port], // Buffer ports
  accType:  AccumType               // Type of accumulator for instance
) {
  def toMemory: Memory = Memory(banking, depth, accType)

  override def toString: String = {
    import scala.math.Ordering.Implicits._

    def prtStr(port: Option[Int], grps: Set[Set[AccessMatrix]], tp: String): Iterator[String] = {
      grps.iterator.flatten
          .filter{a => ports(a).bufferPort == port }    // All accesses on this port
          .toSeq.groupBy(_.access)                      // Group by access (symbol)
          .iterator.flatMap(_._2.sortBy(_.unroll))      // Lexicographic sort by unroll ID
          .map{a => s"  ${port.getOrElse("M")}: (mux:${ports(a).muxPort}) [$tp] ${stm(a.access)} {${a.unroll.mkString(", ")}}" }
    }

    val ps = (0 until depth).map{Some(_)} :+ None

    s"""
       |accumType:  $accType
       |Depth:      $depth
       |Banking:    $banking
       |Controller: ${metapipe.map(_.toString).getOrElse("---")}
       |Buffer Ports:""".stripMargin + "\n" +
    ps.flatMap{port => prtStr(port,writes,"WR") ++ prtStr(port,reads,"RD") }.mkString("\n")
  }

}
object Instance {
  def Unit(rank: Int) = Instance(Set.empty,Set.empty,Set.empty,None,Seq(ModBanking.Unit(rank)),1,0,Map.empty,AccumType.None)
}


/**
  * Abbreviated version of MemoryInstance for use outside memory analysis
  */
case class Memory(
  banking: Seq[Banking],  // Banking information
  depth:   Int,           // Buffer depth
  accType: AccumType      // Flags whether this instance is an accumulator
) {
  def nBanks: Seq[Int] = banking.map(_.nBanks)
  def totalBanks: Int = banking.map(_.nBanks).product

  @api def bankSelects[T:IntLike](addr: Seq[T]): Seq[T] = banking.map(_.bankSelect(addr))

  @api def bankOffset[T:IntLike,C[_]](mem: LocalMem[_,C], addr: Seq[T]): T = {
    import nova.utils.IntLike._
    val w = dimsOf(mem).map(_.toInt)
    val D = rankOf(mem)
    val n = banking.map(_.nBanks).product
    if (banking.lengthIs(1)) {
      val b = banking.head.stride

      Math.meta.sum((0 until D).map{t =>
        val xt = addr(t)
        if (t < D - 1) { xt * (w.slice(t+1,D-1).product * math.ceil(w(D-1).toDouble / (n*b)).toInt * b) }
        else           { (xt / (n*b)) * b + xt % b }
      })
    }
    else if (banking.lengthIs(D)) {
      val b = banking.map(_.stride)
      val n = banking.map(_.nBanks)
      val dims = (0 until D).map{t => (t+1 until D).map{k => math.ceil(w(k)/n(k)).toInt }.product }

      Math.meta.sum((0 until D).map{t =>
        val xt = addr(t)
        ( ( xt/(b(t)*n(t)) )*b(t) + xt%b(t) ) * dims(t)
      })
    }
    else {
      // TODO: Bank address for mixed dimension groups
      throw new Exception("Bank address calculation for arbitrary dim groupings unknown")
    }
  }
}

case class Duplicates(d: Seq[Memory]) extends StableData[Duplicates]
@data object duplicatesOf {
  def get(x: Sym[_]): Option[Seq[Memory]] = metadata[Duplicates](x).map(_.d)
  def apply(x: Sym[_]): Seq[Memory] = duplicatesOf.get(x).getOrElse{throw new Exception(s"No duplicates defined for $x")}
  def update(x: Sym[_], d: Seq[Memory]): Unit = metadata.add(x, Duplicates(d))
}


case class Dispatch(m: Map[Seq[Int],Set[Int]]) extends StableData[Dispatch]
@data object dispatchOf {
  def get(x: Sym[_]): Option[Map[Seq[Int],Set[Int]]] = metadata[Dispatch](x).map(_.m)
  def apply(x: Sym[_]): Map[Seq[Int],Set[Int]] = dispatchOf.get(x).getOrElse{throw new Exception(s"No dispatch defined for $x")}

  def get(x: Sym[_], uid: Seq[Int]): Option[Set[Int]] = dispatchOf.get(x).flatMap(_.get(uid))
  def apply(x: Sym[_], uid: Seq[Int]): Set[Int] = dispatchOf.get(x,uid).getOrElse{throw new Exception(s"No dispatch defined for $x {${uid.mkString(",")}}")}

  def add(x: Sym[_], m: Map[Seq[Int],Set[Int]]): Unit = metadata.add(x, Dispatch(m))
  def add(x: Sym[_], uid: Seq[Int], dispatch: Int): Unit = {
    val map = dispatchOf.get(x).getOrElse(Map.empty)
    val entry = map.getOrElse(uid, Set.empty) + dispatch
    dispatchOf.add(x, map + (uid -> entry))
  }
}

case class Ports(m: Map[Seq[Int],Port]) extends StableData[Ports]
@data object portsOf {
  def get(x: Sym[_]): Option[Map[Seq[Int],Port]] = metadata[Ports](x).map(_.m)
  def apply(x: Sym[_]): Map[Seq[Int],Port] = portsOf.get(x).getOrElse{throw new Exception(s"No ports defined for $x")}

  def get(x: Sym[_], uid: Seq[Int]): Option[Port] = portsOf.get(x).flatMap(_.get(uid))
  def apply(x: Sym[_], uid: Seq[Int]): Port = portsOf.get(x,uid).getOrElse{throw new Exception(s"No ports defined for $x {${uid.mkString(",")}}")}

  def add(x: Sym[_], m: Map[Seq[Int],Port]): Unit = metadata.add(x, Ports(m))
  def add(x: Sym[_], uid: Seq[Int], port: Port): Unit = {
    val map = portsOf.get(x).getOrElse(Map.empty)
    portsOf.add(x, map + (uid -> port))
  }
}