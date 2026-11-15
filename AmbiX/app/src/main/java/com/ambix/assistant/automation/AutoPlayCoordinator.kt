package com.ambix.assistant.automation
import android.os.Handler
import android.os.Looper
import com.ambix.assistant.strategy.PlannedMove
import com.ambix.assistant.vision.BattleSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Puente táctico-accesibilidad, apagado por defecto y con barreras estrictas. */
object AutoPlayCoordinator{
 private val enabled=AtomicBoolean(false)
 private val inFlight=AtomicBoolean(false)
 private val lastActionAt=AtomicLong(0L)
 private val mainHandler=Handler(Looper.getMainLooper())
 val status=AtomicReference("Juego autónomo desactivado")
 fun setEnabled(value:Boolean){enabled.set(value);status.set(if(value)"Armado · esperando lectura completa y fiable" else "Juego autónomo desactivado")}
 fun isEnabled()=enabled.get()
 fun onFrame(snapshot:BattleSnapshot,move:PlannedMove?){
  if(!enabled.get())return
  if(move==null||!move.safeToExecute){status.set("En espera · no hay jugada segura");return}
  if(!snapshot.visionReady||snapshot.hand.size!=4||snapshot.hand.any{it.name.isNullOrBlank()||it.confidence<0.95f}){status.set("Bloqueado · reconocimiento de mano insuficiente");return}
  if(move.confidence<0.90f){status.set("Bloqueado · confianza táctica insuficiente");return}
  val age=System.currentTimeMillis()-snapshot.capturedAtMs
  if(age !in 0L..450L){status.set("Bloqueado · fotograma demasiado antiguo");return}
  val card=snapshot.hand.firstOrNull{it.name.equals(move.card,ignoreCase=true)}
  if(card==null){status.set("Bloqueado · carta recomendada no está en la mano");return}
  val now=System.currentTimeMillis()
  if(now-lastActionAt.get()<1200L||!inFlight.compareAndSet(false,true))return
  val service=GameAutomationService.instance
  if(service==null||!GameAutomationService.connected.get()){inFlight.set(false);status.set("Bloqueado · activá AmbiX Game Controller en Accesibilidad");return}
  status.set("Ejecutando ${move.card} · ${move.lane}")
  mainHandler.post{
   service.playCardAndDeploy(card.bounds,move.targetX,move.targetY,snapshot.frameWidth,snapshot.frameHeight){success,message->
    inFlight.set(false)
    if(success)lastActionAt.set(System.currentTimeMillis())
    status.set(if(success)"Última acción: $message" else "Acción cancelada: $message")
   }
  }
 }
}
