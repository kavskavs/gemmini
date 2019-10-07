package systolic

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import chisel3._
import chisel3.util._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile._
import SystolicISA._

class SystolicDeps extends Bundle {
  val pushStore = Bool()
  val pushLoad = Bool()
  val pushEx = Bool()
  val pullStore = Bool()
  val pullLoad = Bool()
  val pullEx = Bool()
}

class SystolicCmdWithDeps(implicit p: Parameters) extends Bundle {
  val cmd = new RoCCCommand
  val deps = new SystolicDeps

  override def cloneType: this.type = (new SystolicCmdWithDeps).asInstanceOf[this.type]
}

class SPAddr(xLen: Int, sp_banks: Int, sp_bank_entries: Int) extends Bundle {
  val junk = UInt((xLen-log2Ceil(sp_bank_entries)-log2Ceil(sp_banks)).W)
  val bank = UInt(log2Ceil(sp_banks).W)
  val row = UInt(log2Ceil(sp_bank_entries).W)

  override def cloneType: this.type = new SPAddr(xLen, sp_banks, sp_bank_entries).asInstanceOf[this.type]
}

class AccAddr(xLen: Int, acc_rows: Int, tagWidth: Int) extends Bundle {
  val junk = UInt((xLen - tagWidth - log2Ceil(acc_rows)).W)
  val is_acc_addr = Bool()
  val acc = Bool()
  val junk2 = UInt((tagWidth - log2Ceil(acc_rows)-2).W)
  val row = UInt(log2Ceil(acc_rows).W)

  override def cloneType: this.type = new AccAddr(xLen, acc_rows, tagWidth).asInstanceOf[this.type]
}

class SystolicArray[T <: Data : Arithmetic](opcodes: OpcodeSet, val config: SystolicArrayConfig[T])
                                           (implicit p: Parameters)
  extends LazyRoCC (
    opcodes = OpcodeSet.custom3,
    nPTWPorts = 1) {

  Files.write(Paths.get(config.headerFileName), config.generateHeader().getBytes(StandardCharsets.UTF_8))

  val xLen = p(XLen)
  val spad = LazyModule(new Scratchpad(
    config.sp_banks, config.sp_bank_entries,
    new SPAddr(xLen, config.sp_banks, config.sp_bank_entries), // TODO unify this with the other sp_addr
    config))

  override lazy val module = new SystolicArrayModule(this)
  override val tlNode = spad.id_node
}

class SystolicArrayModule[T <: Data: Arithmetic]
    (outer: SystolicArray[T])
    extends LazyRoCCModuleImp(outer)
    with HasCoreParameters {

  import outer.config._
  import outer.spad

  val sp_addr_t = new SPAddr(xLen, sp_banks, sp_bank_entries)
  val funct_t = new Bundle {
    val push1 = UInt(1.W)
    val pop1 = UInt(1.W)
    val push2 = UInt(1.W)
    val pop2 = UInt(1.W)
    val funct = UInt(3.W)
  }

  val tagWidth = 32
  val acc_addr = new AccAddr(xLen, acc_rows, tagWidth)

  // TLB
  implicit val edge = outer.tlNode.edges.out.head
  val tlb = Module(new FrontendTLB(2, 4, dma_maxbytes))
  (tlb.io.clients zip outer.spad.module.io.tlb).foreach(t => t._1 <> t._2)
  tlb.io.exp.flush_skip := false.B
  tlb.io.exp.flush_retry := false.B

  io.ptw.head <> tlb.io.ptw // TODO
  /*io.ptw.head.req <> tlb.io.ptw.req
  tlb.io.ptw.resp <> io.ptw.head.resp
  tlb.io.ptw.ptbr := io.ptw.head.ptbr
  tlb.io.ptw.status := outer.spad.module.io.mstatus
  tlb.io.ptw.pmp := io.ptw.head.pmp
  tlb.io.ptw.customCSRs := io.ptw.head.customCSRs*/

  // Controllers
  val cmd = Queue(io.cmd)
  cmd.ready := false.B

  val funct = cmd.bits.inst.funct.asTypeOf(funct_t).funct
  val push1 = cmd.bits.inst.funct.asTypeOf(funct_t).push1
  val pop1 = cmd.bits.inst.funct.asTypeOf(funct_t).pop1
  val push2 = cmd.bits.inst.funct.asTypeOf(funct_t).push2
  val pop2 = cmd.bits.inst.funct.asTypeOf(funct_t).pop2

  val load_controller = Module(new LoadController(outer.config, coreMaxAddrBits, sp_addr_t, acc_addr))
  val store_controller = Module(new StoreController(outer.config, coreMaxAddrBits, sp_addr_t, acc_addr))
  val ex_controller = Module(new ExecuteController(xLen, tagWidth, outer.config, sp_addr_t, acc_addr))

  // val dma_arbiter = Module(new DMAArbiter(sp_banks, sp_bank_entries, acc_rows))

  val load_to_store_depq = Queue(load_controller.io.pushStore, depq_len)
  val load_to_ex_depq = Queue(load_controller.io.pushEx, depq_len)
  val store_to_load_depq = Queue(store_controller.io.pushLoad, depq_len)
  val store_to_ex_depq = Queue(store_controller.io.pushEx, depq_len)
  val (ex_to_load_depq, ex_to_load_depq_len) = MultiHeadedQueue(ex_controller.io.pushLoad, depq_len, 1)
  val (ex_to_store_depq, ex_to_store_depq_len) = MultiHeadedQueue(ex_controller.io.pushStore, depq_len, 1)

  // Wire up commands to controllers
  load_controller.io.cmd.valid := false.B
  load_controller.io.cmd.bits.cmd := cmd.bits
  load_controller.io.cmd.bits.cmd.inst.funct := funct
  load_controller.io.cmd.bits.deps.pushLoad := false.B
  load_controller.io.cmd.bits.deps.pullLoad := false.B
  load_controller.io.cmd.bits.deps.pushStore := push1
  load_controller.io.cmd.bits.deps.pullStore := pop1
  load_controller.io.cmd.bits.deps.pushEx := push2
  load_controller.io.cmd.bits.deps.pullEx := pop2

  store_controller.io.cmd.valid := false.B
  store_controller.io.cmd.bits.cmd := cmd.bits
  store_controller.io.cmd.bits.cmd.inst.funct := funct
  store_controller.io.cmd.bits.deps.pushLoad := push1
  store_controller.io.cmd.bits.deps.pullLoad := pop1
  store_controller.io.cmd.bits.deps.pushStore := false.B
  store_controller.io.cmd.bits.deps.pullStore := false.B
  store_controller.io.cmd.bits.deps.pushEx := push2
  store_controller.io.cmd.bits.deps.pullEx := pop2

  ex_controller.io.cmd.valid := false.B
  ex_controller.io.cmd.bits.cmd := cmd.bits
  ex_controller.io.cmd.bits.cmd.inst.funct := funct
  ex_controller.io.cmd.bits.deps.pushLoad := push1
  ex_controller.io.cmd.bits.deps.pullLoad := pop1
  ex_controller.io.cmd.bits.deps.pushStore := push2
  ex_controller.io.cmd.bits.deps.pullStore := pop2
  ex_controller.io.cmd.bits.deps.pushEx := false.B
  ex_controller.io.cmd.bits.deps.pullEx := false.B
  ex_controller.io.pushLoadLeft := depq_len.U - ex_to_load_depq_len
  ex_controller.io.pushStoreLeft := depq_len.U - ex_to_store_depq_len

  // Wire up scratchpad to controllers
  // spad.module.io.dma_read <> dma_arbiter.io.dma
  // dma_arbiter.io.load <> load_controller.io.dma
  // dma_arbiter.io.store <> store_controller.io.dma
  spad.module.io.dma.read <> load_controller.io.dma
  spad.module.io.dma.write <> store_controller.io.dma
  ex_controller.io.read <> spad.module.io.read
  ex_controller.io.write <> spad.module.io.write
  ex_controller.io.acc <> spad.module.io.acc

  // Wire up controllers to dependency queues
  load_controller.io.pullStore <> store_to_load_depq
  load_controller.io.pullEx.valid := ex_to_load_depq.valid(0)
  load_controller.io.pullEx.bits := ex_to_load_depq.bits(0)
  ex_to_load_depq.pop := load_controller.io.pullEx.ready
  
  store_controller.io.pullLoad <> load_to_store_depq
  store_controller.io.pullEx.valid := ex_to_store_depq.valid(0)
  store_controller.io.pullEx.bits := ex_to_store_depq.bits(0)
  ex_to_store_depq.pop := store_controller.io.pullEx.ready
  
  ex_controller.io.pullLoad <> load_to_ex_depq
  ex_controller.io.pullStore <> store_to_ex_depq

  // Wire up global RoCC signals
  io.busy := cmd.valid || load_controller.io.busy || store_controller.io.busy || spad.module.io.busy
  io.interrupt := tlb.io.exp.interrupt

  // Issue commands to controllers
  // TODO we combinationally couple cmd.ready and cmd.valid signals here
  when (cmd.valid) {
    val config_cmd_type = cmd.bits.rs1(1,0) // TODO magic numbers

    val is_flush = funct === FLUSH_CMD
    val is_load = (funct === LOAD_CMD) || (funct === CONFIG_CMD && config_cmd_type === CONFIG_LOAD)
    val is_store = (funct === STORE_CMD) || (funct === CONFIG_CMD && config_cmd_type === CONFIG_STORE)
    val is_ex = (funct === COMPUTE_AND_FLIP_CMD || funct === COMPUTE_AND_STAY_CMD || funct === PRELOAD_CMD) ||
      (funct === CONFIG_CMD && config_cmd_type === CONFIG_EX)

    when (is_flush) {
      val skip = cmd.bits.rs1(0)
      tlb.io.exp.flush_skip := skip
      tlb.io.exp.flush_retry := !skip

      cmd.ready := true.B // TODO should we wait for an acknowledgement from the TLB?
    }

    .elsewhen (is_load) {
      load_controller.io.cmd.valid := true.B

      when (load_controller.io.cmd.fire()) {
        cmd.ready := true.B
      }
    }

    .elsewhen (is_store) {
      store_controller.io.cmd.valid := true.B

      when (store_controller.io.cmd.fire()) {
        cmd.ready := true.B
      }
    }

    .otherwise {
      ex_controller.io.cmd.valid := true.B

      when (ex_controller.io.cmd.fire()) {
        cmd.ready := true.B
      }

      assert(is_ex, "unknown systolic command")
    }
  }
}
