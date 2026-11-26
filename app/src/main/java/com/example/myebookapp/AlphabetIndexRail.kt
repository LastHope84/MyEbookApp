package com.example.myebookapp

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AlphabetIndexRail(
    index: AlphabetIndex,
    onJump: (Char) -> Unit,
    modifier: Modifier = Modifier
) {
    val letters = AlphabetIndex.LETTERS
    var active by remember { mutableStateOf<Char?>(null) }
    val haptics = LocalHapticFeedback.current
    val jump by rememberUpdatedState(onJump)
    val actions = remember {
        letters.map { letter ->
            CustomAccessibilityAction(
                if (letter == '#') "Jump to numbers and symbols" else "Jump to $letter"
            ) { jump(letter); true }
        }
    }

    val gesture = Modifier.pointerInput(letters) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var lastSlot = -1
            var current: Char? = null
            fun handle(y: Float) {
                val slot = (y / size.height * letters.size).toInt().coerceIn(0, letters.size - 1)
                if (slot == lastSlot) return
                lastSlot = slot
                val letter = letters[slot]
                if (letter != current) {
                    current = letter
                    active = letter
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                jump(letter)
            }
            try {
                handle(down.position.y)
                down.consume()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    handle(change.position.y)
                    change.consume()
                }
            } finally {
                active = null
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxHeight().systemGestureExclusion()) {
        if (maxHeight < 360.dp) return@BoxWithConstraints
        val slot = maxHeight / letters.size
        val fontSize = with(LocalDensity.current) { (slot.toSp().value * 0.62f).coerceIn(7f, 10f).sp }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(gesture)
                .semantics {
                    contentDescription = "Alphabet index"
                    stateDescription = active?.let { "Letter $it" } ?: "No letter selected"
                    customActions = actions
                }
        ) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                for (letter in letters) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = letter.toString(),
                            color = when {
                                letter == active -> MaterialTheme.colorScheme.primary
                                letter in index.present -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            },
                            fontSize = fontSize,
                            fontWeight = if (letter in index.present) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
            active?.let { letter ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .wrapContentWidth(unbounded = true, align = Alignment.End)
                        .offset(x = (-34).dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shadowElevation = 3.dp
                ) {
                    Column(
                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(letter.toString(), style = MaterialTheme.typography.labelLarge)
                        val count = index.counts[letter] ?: 0
                        if (count > 0) Text("$count", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
