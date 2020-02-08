//See LICENSE for license details
package firesim.bridges

import midas.widgets._

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.subsystem.PeripheryBusKey
import sifive.blocks.devices.uart.{UARTPortIO, PeripheryUARTKey}

// We need to box this Int in a case class for the constructor invocation to
// work correctly.
case class UARTKey(div: Int)

class UARTBridge(implicit p: Parameters) extends BlackBox
    with Bridge[HostPortIO[UARTBridgeTargetIO], UARTBridgeModule] {
  val io = IO(new UARTBridgeTargetIO)
  val bridgeIO = HostPort(io)
/* The previosu code here for baudrate (32 * 115200)
   seems to assume a 3.2 ghz core cpu sim clock 
  val frequency = p(PeripheryBusKey).frequency
  val baudrate = 3686400L
  val div = (p(PeripheryBusKey).frequency / baudrate).toInt
 */
/* earlier versions for Aloe/FSDK used this..
    val baudrate = 115200 * frequency/100000000
    val div = (p(PeripheryBusKey).frequency / baudrate).toInt
 */
  /* assume a 100mhz clock from someplace?? (It works, no idea why) */
  val div = 100000000 / 115200
  val constructorArg = Some(UARTKey(div))
  generateAnnotations()
}

object UARTBridge {
  def apply(clock: Clock, uart: UARTPortIO)(implicit p: Parameters): UARTBridge = {
    val ep = Module(new UARTBridge)
    ep.io.uart <> uart
    ep.io.clock := clock
    ep
  }
}

class UARTBridgeTargetIO extends Bundle {
  val clock = Input(Clock())
  val uart = Flipped(new UARTPortIO)
  val reset = Input(Bool())
}


class UARTBridgeModule(key: UARTKey)(implicit p: Parameters) extends BridgeModule[HostPortIO[UARTBridgeTargetIO]]()(p) {
  val div = key.div
  val io = IO(new WidgetIO())
  val hPort = IO(HostPort(new UARTBridgeTargetIO))

  val txfifo = Module(new Queue(UInt(8.W), 128))
  val rxfifo = Module(new Queue(UInt(8.W), 128))

  val target = hPort.hBits.uart
  val fire = hPort.toHost.hValid && hPort.fromHost.hReady && txfifo.io.enq.ready
  val targetReset = fire & hPort.hBits.reset
  rxfifo.reset := reset.toBool || targetReset
  txfifo.reset := reset.toBool || targetReset

  hPort.toHost.hReady := fire
  hPort.fromHost.hValid := fire

  val sTxIdle :: sTxWait :: sTxData :: sTxBreak :: Nil = Enum(UInt(), 4)
  val txState = RegInit(sTxIdle)
  val txData = Reg(UInt(8.W))
  // iterate through bits in byte to deserialize
  val (txDataIdx, txDataWrap) = Counter(txState === sTxData && fire, 8)
  // iterate using div to convert clock rate to baud
  val (txBaudCount, txBaudWrap) = Counter(txState === sTxWait && fire, div)
  val (txSlackCount, txSlackWrap) = Counter(txState === sTxIdle && target.txd === 0.U && fire, 4)

  switch(txState) {
    is(sTxIdle) {
      when(txSlackWrap) {
        txData  := 0.U
        txState := sTxWait
      }
    }
    is(sTxWait) {
      when(txBaudWrap) {
        txState := sTxData
      }
    }
    is(sTxData) {
      when(fire) {
        txData := txData | (target.txd << txDataIdx)
      }
      when(txDataWrap) {
        txState := Mux(target.txd === 1.U, sTxIdle, sTxBreak)
      }.elsewhen(fire) {
        txState := sTxWait
      }
    }
    is(sTxBreak) {
      when(target.txd === 1.U && fire) {
        txState := sTxIdle
      }
    }
  }

  txfifo.io.enq.bits  := txData
  txfifo.io.enq.valid := txDataWrap

  val sRxIdle :: sRxStart :: sRxData :: Nil = Enum(UInt(), 3)
  val rxState = RegInit(sRxIdle)
  // iterate using div to convert clock rate to baud
  val (rxBaudCount, rxBaudWrap) = Counter(fire, div)
  // iterate through bits in byte to deserialize
  val (rxDataIdx, rxDataWrap) = Counter(rxState === sRxData && fire && rxBaudWrap, 8)

  target.rxd := 1.U
  switch(rxState) {
    is(sRxIdle) {
      target.rxd := 1.U
      when (rxBaudWrap && rxfifo.io.deq.valid) {
        rxState := sRxStart
      }
    }
    is(sRxStart) {
      target.rxd := 0.U
      when(rxBaudWrap) {
        rxState := sRxData
      }
    }
    is(sRxData) {
      target.rxd := (rxfifo.io.deq.bits >> rxDataIdx)(0)
      when(rxDataWrap && rxBaudWrap) {
        rxState := sRxIdle
      }
    }
  }
  rxfifo.io.deq.ready := (rxState === sRxData) && rxDataWrap && rxBaudWrap && fire

  genROReg(txfifo.io.deq.bits, "out_bits")
  genROReg(txfifo.io.deq.valid, "out_valid")
  Pulsify(genWORegInit(txfifo.io.deq.ready, "out_ready", false.B), pulseLength = 1)

  genWOReg(rxfifo.io.enq.bits, "in_bits")
  Pulsify(genWORegInit(rxfifo.io.enq.valid, "in_valid", false.B), pulseLength = 1)
  genROReg(rxfifo.io.enq.ready, "in_ready")

  genCRFile()
}
