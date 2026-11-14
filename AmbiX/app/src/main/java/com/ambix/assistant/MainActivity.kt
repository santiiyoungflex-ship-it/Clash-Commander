package com.ambix.assistant
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val signatures=mapOf(
 "Hog cycle" to setOf("Hog Rider","Firecracker","Cannon","Earthquake","Ice Spirit","Skeletons","Musketeer"),
 "Log bait" to setOf("Goblin Barrel","Princess","Goblin Gang","Knight","Rocket","Inferno Tower","Dart Goblin"),
 "Giant beatdown" to setOf("Giant","Witch","Night Witch","Prince","Mini P.E.K.K.A","Lightning","Mega Minion"),
 "Bridge spam" to setOf("Battle Ram","Bandit","P.E.K.K.A","Royal Ghost","Magic Archer","Electro Wizard"),
 "LavaLoon" to setOf("Lava Hound","Balloon","Inferno Dragon","Mega Minion","Tombstone","Arrows")
)
private fun infer(seen:List<String>):Pair<String,Int>{
 if(seen.isEmpty()) return "Datos insuficientes" to 0
 val best=signatures.mapValues{(_,s)->seen.distinct().count{s.contains(it)}}
  .filterValues{it>0}.maxByOrNull{it.value} ?: return "Arquetipo desconocido" to 0
 return best.key to (best.value*15+seen.distinct().size*4).coerceAtMost(85)
}
private val ownDeck=listOf("Princess","Prince","Goblin Barrel","The Log","Guards","Rocket","Bandit","Rascals")
private val costs=mapOf("Princess" to 3,"Prince" to 5,"Goblin Barrel" to 3,"The Log" to 2,"Guards" to 3,"Rocket" to 6,"Bandit" to 3,"Rascals" to 5)
private fun suggestion(seen:List<String>,elixir:Int):Pair<String,String>{
 val counters=mapOf("Hog Rider" to listOf("Guards","Prince"),"Balloon" to listOf("Rocket","Princess"),"Giant" to listOf("Prince","Guards"),"Mega Knight" to listOf("Prince","Guards"),"Goblin Barrel" to listOf("The Log"),"P.E.K.K.A" to listOf("Guards","Rascals"))
 val threat=seen.lastOrNull()
 val answer=counters[threat].orEmpty().firstOrNull{(costs[it]?:9)<=elixir}
 if(answer!=null)return answer to "Respuesta orientativa contra $threat; comprobá distancia, apoyo y posición."
 val affordable=ownDeck.filter{(costs[it]?:9)<=elixir}.minByOrNull{costs[it]?:9}
 return if(affordable!=null) affordable to "Ciclo económico. Evitá comprometer demasiado elixir sin leer el campo."
 else "Esperar" to "No hay una respuesta asequible con el elixir indicado."
}
class MainActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{Screen()}}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Screen(){
 var seen by remember{mutableStateOf(listOf<String>())}
 var elixir by remember{mutableIntStateOf(5)}
 val choices=listOf("Hog Rider","Goblin Barrel","Giant","Mega Knight","Balloon","Lava Hound","P.E.K.K.A","Battle Ram","Royal Hogs","Firecracker","Princess","Prince","The Log","Rocket","Bandit","Guards","Rascals","Cannon","Earthquake","Musketeer","Witch","Goblin Gang","Inferno Tower","Dart Goblin")
 val (arch,confidence)=infer(seen)
 val (move,why)=suggestion(seen,elixir)
 MaterialTheme { Scaffold(topBar={TopAppBar(title={Text("AmbiX · Asistente táctico")})}){p->
  LazyColumn(Modifier.fillMaxSize().padding(p).padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
   item{Text("MVP de análisis manual",style=MaterialTheme.typography.titleMedium);Text("Registrá las cartas rivales observadas. No se puede conocer con certeza su mano ni sus cartas ocultas.")}
   item{Text("Tu elixir: $elixir");Slider(value=elixir.toFloat(),onValueChange={elixir=it.toInt()},valueRange=0f..10f,steps=9)}
   item{Text("Cartas rivales: "+(if(seen.isEmpty())"ninguna" else seen.joinToString(" · ")));Row{TextButton(onClick={if(seen.isNotEmpty())seen=seen.dropLast(1)}){Text("Deshacer")};TextButton(onClick={seen= emptyList()}){Text("Limpiar")}}}
   item{Text("Tocá la carta rival cuando aparezca:")}
   items(choices){c->OutlinedButton(onClick={seen=(seen+c).takeLast(16)},modifier=Modifier.fillMaxWidth()){Text(c)}}
   item{HorizontalDivider();Text("Rival probable: $arch");Text("Confianza orientativa: $confidence%");Text("Jugada sugerida: $move",style=MaterialTheme.typography.titleLarge);Text(why);Text("Mazo inicial: "+ownDeck.joinToString(" · "));Text("Estas reglas son heurísticas preliminares, no una IA competitiva validada.")}
  }
 }}
}
