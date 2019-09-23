package systolic

import chisel3._
import chisel3.util._

import Util._

// Reserves space for a new block of data from main memory
// "tag" is ignored in all cycles between when ReservationBufferAlloc first fires and when "last" is high
class ReservationBufferAlloc[Tag <: Data](tag_t: Tag) extends Bundle {
  val tag = tag_t.cloneType

  override def cloneType: this.type = new ReservationBufferAlloc(tag_t.cloneType).asInstanceOf[this.type]
}

// Describes the source ID of every TileLink request that makes up the reserved block of data from main memory
class ReservationBufferAllocStream(nXacts: Int, rowLen: Int) extends Bundle {
  val xact_id = UInt(log2Ceil(nXacts).W)
  val len = UInt(log2Ceil(rowLen+1).W)
  val last = Bool()

  override def cloneType: this.type = new ReservationBufferAllocStream(nXacts, rowLen).asInstanceOf[this.type]
}

// The data returned from TileLink with the source ID of the request
class ReservationBufferIn[T <: Data](gen: T, nXacts: Int) extends Bundle {
  val data = gen.cloneType
  val xact_id = UInt(log2Ceil(nXacts).W)

  override def cloneType: this.type = new ReservationBufferIn(gen.cloneType, nXacts).asInstanceOf[this.type]
}

// Outputs the collected, merged data from TileLink and frees up a row in the ReservationBuffer
class ReservationBufferOut[T <: Data, Tag <: Data](gen: T, tag_t: Tag, rowLen: Int) extends Bundle {
  val data = Vec(rowLen, gen.cloneType)
  val tag = tag_t.cloneType
  val valid = Vec(rowLen, Bool())

  override def cloneType: this.type = new ReservationBufferOut(gen.cloneType, tag_t.cloneType, rowLen).asInstanceOf[this.type]
}

class ReservationBufferIO[T <: Data, Tag <: Data](gen: T, tag_t: Tag, nXacts: Int, rowLen: Int) extends Bundle {
  val alloc = Flipped(Decoupled(new ReservationBufferAlloc(tag_t.cloneType)))
  val alloc_stream = Flipped(Decoupled(new ReservationBufferAllocStream(nXacts, rowLen))) // TODO make this valid
  val in = Flipped(Valid(new ReservationBufferIn(gen.cloneType, nXacts)))
  val out = Decoupled(new ReservationBufferOut(gen.cloneType, tag_t.cloneType, rowLen))

  override def cloneType: this.type = new ReservationBufferIO(gen.cloneType, tag_t.cloneType, nXacts, rowLen).asInstanceOf[this.type]
}

// A special case of the ReservationBufferIO which is for modules which only want to allocate and forward data to the
// reservation buffer, without reading its output
class ReservationBufferAllocatorIO[T <: Data, Tag <: Data](gen: T, tag_t: Tag, nXacts: Int, rowLen: Int) extends Bundle {
  val alloc = Decoupled(new ReservationBufferAlloc(tag_t.cloneType))
  val alloc_stream = Decoupled(new ReservationBufferAllocStream(nXacts, rowLen)) // TODO make this valid
  val in = Valid(new ReservationBufferIn(gen.cloneType, nXacts))

  override def cloneType: this.type = new ReservationBufferAllocatorIO(gen.cloneType, tag_t.cloneType, nXacts, rowLen).asInstanceOf[this.type]
}

// gen: What datatype is this buffer going to store? For our purposes, this will probably be an unsigned integer with a
//        bitwidth of min(beatBits, spadWidth)
// tag_t: The entries in the reservation buffer must have metadata (a "tag") associated with them, so that the recipient
//        can re-order them. This parameter defines the datatype of that tag
// nXacts: How many transactions can be in flight at any one time? Or, in other words, how many possible sourceIDs are
//        there?
// rowLen: What is the maximum length of a row? Each row is made up of some number of entries of type "gen"
class ReservationBuffer[T <: Data, Tag <: Data](gen: T, tag_t: Tag, nXacts: Int, rowLen: Int) extends Module {
  val io = IO(new ReservationBufferIO(gen.cloneType, tag_t.cloneType, nXacts, rowLen))

  val nEntries = nXacts

  class Entry extends Bundle {
    val data = Vec(rowLen, UDValid(gen))
    val tag = tag_t.cloneType
    val xact_ids = Vec(rowLen, UDValid(UInt(log2Ceil(nXacts).W)))
    val alloc_done = Bool()
    val valid = Bool()

    def ready_for_output(dummy: Int = 0): Bool = valid && alloc_done &&
      (xact_ids zip data).map { case (xid, d) => xid.valid === d.valid }.reduce(_ && _)

    def init(): Unit = {
      data.foreach(_.valid := false.B)
      xact_ids.foreach(_.valid := false.B)
      alloc_done := false.B
      valid := false.B
    }
  }

  val allocating = RegInit(false.B)
  val allocating_to = Reg(UInt(log2Ceil(nEntries).W))

  val entries = Reg(Vec(rowLen, new Entry))

  val alloc_free_row = MuxCase(0.U, entries.zipWithIndex.map { case (e, i) => !e.valid -> i.U })
  val output_row = MuxCase(0.U, entries.zipWithIndex.map { case (e, i) => e.ready_for_output() -> i.U })

  io.alloc.ready := !entries.map(_.valid).reduce(_ && _) && !allocating
  io.alloc_stream.ready := io.alloc.fire() || allocating
  io.out.valid := entries.map(_.ready_for_output()).reduce(_ || _)
  io.out.bits.data := entries(output_row).data.map(_.bits)
  io.out.bits.tag := entries(output_row).tag
  io.out.bits.valid := entries(output_row).data.map(_.valid)

  // When we allocate a row
  when(io.alloc.fire()) {
    entries(alloc_free_row).valid := true.B
    entries(alloc_free_row).tag := io.alloc.bits.tag
    allocating := !(io.alloc_stream.fire() && io.alloc_stream.bits.last)
  }

  // When we stream in alloc xact_ids
  when(io.alloc_stream.fire()) {
    val row_id = Mux(allocating, allocating_to, alloc_free_row)
    // val xact_id_id = entries(row_id).xact_ids.foldLeft(0.U)(_ +& _.valid)
    val xact_id_id = PopCount(entries(row_id).xact_ids.map(_.valid))

    entries(row_id).xact_ids.zipWithIndex.foreach { case (xid, i) =>
      val lo = xact_id_id
      val hi = xact_id_id +& io.alloc_stream.bits.len
      when (i.U >= lo && i.U < hi) {
        xid.bits := io.alloc_stream.bits.xact_id
        xid.valid := true.B
      }
    }

    allocating := !io.alloc_stream.bits.last
    entries(row_id).alloc_done := io.alloc_stream.bits.last
  }

  // When previously allocated data is input
  when (io.in.fire()) {
    val not_previously_allocated = WireInit(true.B)

    entries.foreach { e =>
      val contains_xact_id = (e.xact_ids zip e.data)
        .map { case (xid, d) =>
          // Look for the matching xact_id, but make sure it hasn't already been filled with a beat of data
          xid.bits === io.in.bits.xact_id && xid.valid && !d.valid
        }.reduce(_ || _)

      val data_valid_id = MuxCase(0.U, (e.xact_ids zip e.data).zipWithIndex.map { case ((xid, d), i) =>
        // Look for the matching xact_id, but make sure it hasn't already been filled with a beat of data
        (xid.bits === io.in.bits.xact_id && xid.valid && !d.valid) -> i.U
      })

      when (e.valid && contains_xact_id) {
        e.data(data_valid_id).bits := io.in.bits.data
        e.data(data_valid_id).valid := true.B
        not_previously_allocated := false.B
      }
    }

    assert(!not_previously_allocated, "previously unallocated variable was inputted")
  }

  // When data is finally outputted
  when (io.out.fire()) {
    entries(output_row).init()
  }

  // This is a workaround for the lack of bundle literals
  when (reset.asBool()) {
    entries.foreach(_.init())
  }
}