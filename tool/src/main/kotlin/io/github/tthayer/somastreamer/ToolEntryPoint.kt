package io.github.tthayer.somastreamer

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint

// No push notifications and no app server: the tool talks only to SomaFM, so
// the default (no-op) LightEntryPoint hooks are all it needs.
@EntryPoint
object ToolEntryPoint : LightEntryPoint
