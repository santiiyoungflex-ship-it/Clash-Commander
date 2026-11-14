package com.ambix.assistant.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicReference

data class FrameReport(val width:Int,val height:Int,val sampledPixels:Int,val bluePixels:Int,val purplePixels:Int,val capturedAt:Long)
object VisionState {
 val latest=AtomicReference<FrameReport?>(null)
 val status=AtomicReference("Captura detenida")
}
class ScreenCaptureService:Service(){
 private var projection:MediaProjection?=null
 private var reader:ImageReader?=null
 private var display:VirtualDisplay?=null
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  if(intent?.action=="STOP"){stopCapture();stopSelf();return START_NOT_STICKY}
  val result=intent?.getIntExtra(EXTRA_RESULT,0)?:0
  val data=if(Build.VERSION.SDK_INT>=33) intent?.getParcelableExtra(EXTRA_DATA,Intent::class.java)
   else @Suppress("DEPRECATION") (intent?.getParcelableExtra(EXTRA_DATA) as? Intent)
  if(data==null){VisionState.status.set("Falta autorización de captura");stopSelf();return START_NOT_STICKY}
  createChannel()
  val n=Notification.Builder(this,CHANNEL).setContentTitle("AmbiX").setContentText("Captura de pantalla activa")
   .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
  if(Build.VERSION.SDK_INT>=29)startForeground(41,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(41,n)
  try{
   val manager=getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
   projection=manager.getMediaProjection(result,data)
   projection?.registerCallback(object:MediaProjection.Callback(){override fun onStop(){stopCapture();stopSelf()}},null)
   val wm=getSystemService(WINDOW_SERVICE) as WindowManager
   val metrics=DisplayMetrics()
   @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
   val w=metrics.widthPixels;val h=metrics.heightPixels
   reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
   reader?.setOnImageAvailableListener({ir->
    var img:Image?=null
    try{img=ir.acquireLatestImage();if(img!=null)analyze(img!!)}
    catch(_:Throwable){VisionState.status.set("Error procesando fotograma")}
    finally{img?.close()}
   },null)
   display=projection?.createVirtualDisplay("AmbiX-screen",w,h,metrics.densityDpi,
    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader?.surface,null,null)
   VisionState.status.set("Captura activa; esperando fotogramas")
  }catch(t:Throwable){VisionState.status.set("Error de captura: ${t.javaClass.simpleName}");stopCapture();stopSelf()}
  return START_NOT_STICKY
 }
 private fun analyze(image:Image){
  val p=image.planes[0];val buf=p.buffer;val stride=p.pixelStride;val rowStride=p.rowStride
  val w=image.width;val h=image.height;val rowPixels=w+(rowStride-stride*w)/stride
  val bytes=ByteArray(buf.remaining());buf.get(bytes)
  var blue=0;var purple=0;var samples=0
  val sx=(w/160).coerceAtLeast(1);val sy=(h/280).coerceAtLeast(1)
  var y=h/2
  while(y<h){var x=0;while(x<w){
   val i=(y*rowPixels+x)*stride
   if(i+2<bytes.size){val r=bytes[i].toInt() and 255;val g=bytes[i+1].toInt() and 255;val b=bytes[i+2].toInt() and 255
    if(b>r*1.25&&b>g*1.1&&b>75)blue++
    if(r>90&&b>90&&b>g*1.15&&r>g*1.1)purple++
    samples++
   };x+=sx
  };y+=sy}
  VisionState.latest.set(FrameReport(w,h,samples,blue,purple,System.currentTimeMillis()))
  VisionState.status.set("Fotograma leído · ${w}×${h} · azul $blue · púrpura $purple")
 }
 private fun stopCapture(){display?.release();display=null;reader?.close();reader=null;projection?.stop();projection=null;VisionState.status.set("Captura detenida")}
 private fun createChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Captura AmbiX",NotificationManager.IMPORTANCE_LOW))}
 override fun onDestroy(){stopCapture();super.onDestroy()}
 companion object{const val EXTRA_RESULT="capture_result";const val EXTRA_DATA="capture_data";const val CHANNEL="ambix_capture"}
}