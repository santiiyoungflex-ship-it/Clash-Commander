package com.ambix.assistant.automation
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.ambix.assistant.vision.Box
import java.util.concurrent.atomic.AtomicBoolean

class GameAutomationService : AccessibilityService() {
 @Volatile private var gameForeground=false
 private val gestureBusy=AtomicBoolean(false)
 private val mainHandler=Handler(Looper.getMainLooper())
 override fun onServiceConnected(){super.onServiceConnected();instance=this;connected.set(true)}
 override fun onAccessibilityEvent(event:AccessibilityEvent?){
  val pkg=event?.packageName?.toString()
  if(pkg!=null) gameForeground=pkg==CLASH_ROYALE_PACKAGE
 }
 override fun onInterrupt(){gameForeground=false}
 override fun onDestroy(){gameForeground=false;if(instance===this)instance=null;connected.set(false);super.onDestroy()}
 fun playCardAndDeploy(cardBounds:Box,targetX:Float,targetY:Float,frameWidth:Int,frameHeight:Int,callback:(Boolean,String)->Unit){
  if(Build.VERSION.SDK_INT<Build.VERSION_CODES.N){callback(false,"Android no admite gestos de accesibilidad");return}
  if(!connected.get()||!gameForeground){callback(false,"Clash Royale no está en primer plano o falta Accesibilidad");return}
  if(frameWidth<=0||frameHeight<=0||targetX !in 0f..1f||targetY !in 0f..1f||cardBounds.centerX !in 0f..1f||cardBounds.centerY !in 0f..1f){callback(false,"Coordenadas inválidas");return}
  if(!gestureBusy.compareAndSet(false,true)){callback(false,"Ya hay una jugada en ejecución");return}
  val cx=(cardBounds.centerX*frameWidth).coerceIn(1f,(frameWidth-2).coerceAtLeast(1).toFloat())
  val cy=(cardBounds.centerY*frameHeight).coerceIn(1f,(frameHeight-2).coerceAtLeast(1).toFloat())
  val tx=(targetX*frameWidth).coerceIn(1f,(frameWidth-2).coerceAtLeast(1).toFloat())
  val ty=(targetY*frameHeight).coerceIn(1f,(frameHeight-2).coerceAtLeast(1).toFloat())
  dispatchTap(cx,cy){selected->
   if(!selected||!gameForeground){gestureBusy.set(false);callback(false,"No se pudo seleccionar la carta");return@dispatchTap}
   mainHandler.postDelayed({
    if(!gameForeground){gestureBusy.set(false);callback(false,"Clash Royale dejó de estar en primer plano")}
    else dispatchTap(tx,ty){deployed->gestureBusy.set(false);callback(deployed,if(deployed)"Carta desplegada" else "Falló el toque de despliegue")}
   },120L)
  }
 }
 private fun dispatchTap(x:Float,y:Float,callback:(Boolean)->Unit){
  val path=Path().apply{moveTo(x,y)}
  val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0L,55L)).build()
  val accepted=dispatchGesture(gesture,object:GestureResultCallback(){
   override fun onCompleted(gestureDescription:GestureDescription?)=callback(true)
   override fun onCancelled(gestureDescription:GestureDescription?)=callback(false)
  },null)
  if(!accepted)callback(false)
 }
 companion object{
  const val CLASH_ROYALE_PACKAGE="com.supercell.clashroyale"
  @Volatile var instance:GameAutomationService?=null
   private set
  val connected=AtomicBoolean(false)
 }
}
