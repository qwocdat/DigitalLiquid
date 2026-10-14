package com.digitalsmod.digitalliquid

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class DigitalLiquidVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        DigitalLiquidVoiceInteractionSession(this)
}
