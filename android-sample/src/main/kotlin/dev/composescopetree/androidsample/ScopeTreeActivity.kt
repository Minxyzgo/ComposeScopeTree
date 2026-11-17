package dev.composescopetree.androidsample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.composescopetree.ImplicitScopeTreeHost
import dev.composescopetree.ui.BoxComponent

public class ScopeTreeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = ImplicitScopeTreeHost()
        val panelColor = Color(0xFFC9DCF2)
        setContent {
            var expanded by remember { mutableStateOf(false) }
            val panel = host.roots.singleOrNull { it.parameters(BoxComponent) != null }

            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    if (expanded) "Restore box size" else "Expand box with a scope edit",
                    Modifier.clickable(enabled = panel != null) {
                        val address = panel?.address ?: return@clickable
                        expanded = !expanded
                        if (expanded) {
                            host.overrideParameters(address, BoxComponent) {
                                it.copy(modifier = Modifier.size(160.dp).background(panelColor))
                            }
                        } else {
                            host.resetParameters(address)
                        }
                    }.padding(12.dp),
                )

                host.Content {
                    Box(
                        modifier = Modifier.size(96.dp).background(panelColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("Ordinary Box")
                    }
                }
            }
        }
    }
}
