package com.yoyostudios.clashcompanion.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoyostudios.clashcompanion.MainActivity
import com.yoyostudios.clashcompanion.R
import com.yoyostudios.clashcompanion.deck.DeckManager
import com.yoyostudios.clashcompanion.ui.components.CrButtonGold
import com.yoyostudios.clashcompanion.ui.components.CrCard
import com.yoyostudios.clashcompanion.ui.components.CrCrown
import com.yoyostudios.clashcompanion.ui.components.ElixirDrop
import com.yoyostudios.clashcompanion.ui.components.crBackground
import com.yoyostudios.clashcompanion.ui.theme.CrColors
import com.yoyostudios.clashcompanion.ui.theme.CrTypography

@Composable
fun MainScreen(activity: MainActivity) {
    val overlayOk by activity.overlayGranted
    val accessibilityOk by activity.accessibilityEnabled
    val micOk by activity.micGranted
    val captureOk by activity.captureRunning
    val speechReady by activity.speechReady
    val speechLoading by activity.speechLoading
    val deck by activity.deckCards
    val deckModelWarning by activity.deckModelWarning
    val opusStatus by activity.opusStatus
    val opusComplete by activity.opusComplete
    val geminiKeyConfigured by activity.geminiKeyConfigured

    val allReady = overlayOk && accessibilityOk && micOk && captureOk && speechReady

    Box(
        modifier = Modifier
            .fillMaxSize()
            .crBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 32.dp, bottom = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Crown + Title ──
            CrCrown(size = 52.dp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "CLASH COMMANDER",
                style = CrTypography.displayLarge,
                color = CrColors.TextGold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Play Clash Royale With Your Voice",
                style = CrTypography.bodyMedium,
                color = CrColors.TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Unified Checklist Card ──
            CrCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = 0.dp
            ) {
                Column {
                    // Deck loading prompt — always visible at top
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Open Clash Royale so user can share deck
                                val launchIntent = activity.packageManager
                                    .getLaunchIntentForPackage("com.supercell.clashroyale")
                                if (launchIntent != null) {
                                    launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    activity.startActivity(launchIntent)
                                } else {
                                    // CR not installed — open Play Store listing
                                    val storeIntent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.supercell.clashroyale")
                                    )
                                    activity.startActivity(storeIntent)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (deck.isNotEmpty()) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_check_filled),
                                contentDescription = "Done",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(26.dp)
                            )
                        } else {
                            CrCrown(size = 26.dp)
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "Share Deck from Clash Royale",
                            style = CrTypography.titleMedium,
                            color = CrColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (deck.isNotEmpty()) "LOADED" else "OPEN CR",
                            style = CrTypography.labelLarge,
                            color = if (deck.isNotEmpty()) CrColors.Green else CrColors.TextGold,
                            fontSize = 12.sp
                        )
                    }
                    ChecklistDivider()
                    ChecklistRow(
                        step = 2,
                        name = "Screen Overlay",
                        ready = overlayOk,
                        onClick = { activity.requestOverlayPermission() }
                    )
                    ChecklistDivider()
                    ChecklistRow(
                        step = 3,
                        name = "Tap Control",
                        ready = accessibilityOk,
                        onClick = { activity.requestAccessibility() }
                    )
                    ChecklistDivider()
                    ChecklistRow(
                        step = 4,
                        name = "Microphone",
                        ready = micOk,
                        onClick = { activity.requestMicPermission() }
                    )
                    ChecklistDivider()
                    ChecklistRow(
                        step = 5,
                        name = "Screen Capture",
                        ready = captureOk,
                        onClick = { activity.startScreenCapture() }
                    )
                    ChecklistDivider()
                    ChecklistRow(
                        step = 6,
                        name = "Voice Engine",
                        ready = speechReady,
                        loading = speechLoading,
                        onClick = { activity.startSpeechService() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Secure Gemini API setup ──
            GeminiKeyCard(
                configured = geminiKeyConfigured,
                onSave = activity::saveGeminiApiKey,
                onGetKey = activity::openGeminiApiKeyPage
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Deck Section ──
            if (deck.isEmpty()) {
                DeckEmptyCard()
            } else {
                DeckCard(
                    cards = deck,
                    modelWarning = deckModelWarning,
                    opusStatus = opusStatus,
                    opusComplete = opusComplete
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        // ── Sticky Launch Button ──
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            CrButtonGold(
                text = "LAUNCH COMMANDER",
                onClick = { activity.launchOverlay() },
                enabled = allReady,
                icon = painterResource(id = R.drawable.ic_sword_crossed)
            )
        }
    }
}

// ── Checklist Row ──

@Composable
private fun ChecklistRow(
    step: Int,
    name: String,
    ready: Boolean,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!ready && !loading) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Step number in gold circle or green check
        if (ready) {
            AnimatedVisibility(visible = true, enter = scaleIn()) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_check_filled),
                    contentDescription = "Done",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(26.dp)
                )
            }
        } else {
            Surface(
                shape = CircleShape,
                color = if (loading) CrColors.Cyan else CrColors.Gold,
                modifier = Modifier.size(26.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = step.toString(),
                        style = CrTypography.labelLarge,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = name,
            style = CrTypography.titleMedium,
            color = CrColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = when {
                ready -> "READY"
                loading -> "LOADING..."
                else -> "TAP"
            },
            style = CrTypography.labelLarge,
            color = when {
                ready -> CrColors.Green
                loading -> CrColors.Cyan
                else -> CrColors.TextGold
            },
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ChecklistDivider() {
    HorizontalDivider(
        color = CrColors.TealBorder.copy(alpha = 0.3f),
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
}

// ── Deck Empty State ──

@Composable
private fun DeckEmptyCard() {
    CrCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = CrColors.GoldDark.copy(alpha = 0.4f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CrCrown(size = 32.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No deck loaded",
                style = CrTypography.titleMedium,
                color = CrColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Share a deck link from Clash Royale",
                style = CrTypography.bodyMedium,
                color = CrColors.TextGold
            )
        }
    }
}

// ── Deck Card with Grid + Elixir + Opus ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckCard(
    cards: List<DeckManager.CardInfo>,
    modelWarning: String?,
    opusStatus: String,
    opusComplete: Boolean
) {
    CrCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 8.dp
    ) {
        Column {
            // Header row: DECK title + avg elixir
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DECK",
                    style = CrTypography.headlineMedium,
                    color = CrColors.TextPrimary
                )
                Spacer(modifier = Modifier.weight(1f))
                if (cards.isNotEmpty()) {
                    ElixirDrop(size = 14.dp)
                    Spacer(modifier = Modifier.width(3.dp))
                    val avg = cards.map { it.elixir }.average()
                    Text(
                        text = "%.1f".format(avg),
                        style = CrTypography.titleMedium,
                        color = CrColors.TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Card grid: two rows of 4, tight spacing
            val topRow = cards.take(4)
            val bottomRow = cards.drop(4)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                topRow.forEach { card ->
                    CardTile(card = card, modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                bottomRow.forEach { card ->
                    CardTile(card = card, modifier = Modifier.weight(1f))
                }
            }

            // Model support warning (only shown when deck contains unsupported cards)
            if (!modelWarning.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = modelWarning,
                    style = CrTypography.labelSmall,
                    color = CrColors.Error,
                    lineHeight = 14.sp
                )
            }

            // Opus status inside deck card
            if (opusStatus.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(
                    color = CrColors.TealBorder.copy(alpha = 0.3f),
                    thickness = 1.dp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CrCrown(size = 18.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (opusComplete) "Strategy Ready" else "Analyzing...",
                        style = CrTypography.titleMedium,
                        color = if (opusComplete) CrColors.TextGold else CrColors.SmartPurple,
                        modifier = Modifier.weight(1f)
                    )
                    if (opusComplete) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_check_filled),
                            contentDescription = "Done",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Single Card Tile ──

@Composable
private fun CardTile(card: DeckManager.CardInfo, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            // Card art image -- full size, no border, no clipping
            AsyncImage(
                model = DeckManager.getCardImageUrl(card),
                contentDescription = card.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )

            // Elixir badge (top-left corner)
            Surface(
                shape = CircleShape,
                color = CrColors.ElixirPink,
                modifier = Modifier
                    .size(18.dp)
                    .align(Alignment.TopStart)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = card.elixir.toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        style = CrTypography.labelLarge
                    )
                }
            }
        }
        // Card name
        Text(
            text = card.name,
            style = CrTypography.labelSmall,
            color = CrColors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 9.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GeminiKeyCard(
    configured: Boolean,
    onSave: (String) -> Unit,
    onGetKey: () -> Unit
) {
    var key by rememberSaveable { mutableStateOf("") }
    CrCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 16.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "AI connection",
                style = CrTypography.titleMedium,
                color = CrColors.TextGold
            )
            Text(
                text = if (configured) {
                    "Gemini key saved securely on this phone."
                } else {
                    "Add a Gemini API key to enable live arena analysis and Autopilot."
                },
                style = CrTypography.bodyMedium,
                color = CrColors.TextSecondary
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gemini API key") },
                placeholder = { Text("Paste your key here") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        onSave(key.trim())
                        key = ""
                    },
                    enabled = key.trim().length >= 20,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("SAVE KEY")
                }
                TextButton(onClick = onGetKey) {
                    Text("GET KEY")
                }
            }
            Text(
                text = "The key is encrypted with Android Keystore and stays on this device.",
                style = CrTypography.labelSmall,
                color = CrColors.TextSecondary
            )
        }
    }
}

