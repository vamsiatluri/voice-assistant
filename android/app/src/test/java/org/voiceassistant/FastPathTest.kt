package org.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class FastPathTest {
    // Through understand(), the same gate the phone uses (no on-device model in a unit test, so it is the strict pattern).
    private fun s(t: String) = FastPath.understand(t)?.let { listOf(it.action, it.app, it.level.toString()).filter { x -> x.isNotEmpty() }.joinToString(" ") } ?: "brain"

    @Test fun handledOnThePhone() {
        val cases = mapOf(
            "Turn the TV off" to "tv_off 0", "turn off the TV" to "tv_off 0", "${Persona.WAKE_PHRASE} turn the TV on please" to "tv_on 0", "TV on" to "tv_on 0",
            "Put on Netflix" to "open netflix 0", "open Disney plus on the TV" to "open disney+ 0", "launch YouTube" to "open youtube 0",
            "open prime" to "open prime video 0", "put on HBO Max" to "open max 0",
            "turn the TV volume to 25" to "volume_set 25", "set the volume to 10" to "volume_set 10", "volume 30" to "volume_set 30",
            "turn the volume up" to "volume_up 5", "turn the TV down" to "volume_down 5", "volume up by 10" to "volume_up 10", "louder" to "volume_up 5",
            "make it quieter" to "volume_down 5", "mute the TV" to "mute 0", "unmute the TV" to "unmute 0", "car mode on" to "car_on 0",
            "pause the TV" to "pause 0", "pause" to "pause 0", "unpause" to "play 0", "resume" to "play 0",
            "go back 30 seconds" to "skip_back 30", "rewind 2 minutes" to "skip_back 120", "skip ahead 30 seconds" to "skip_forward 30",
            "fast forward" to "skip_forward 10", "Stop, open Hulu" to "open hulu 0", "no put on Netflix" to "open netflix 0", "Stop" to "hush 0", "never mind" to "hush 0", "cancel" to "hush 0", "stop it please" to "hush 0",
        )
        for ((said, want) in cases) assertEquals(said, want, s(said))
    }

    @Test fun leftToTheBrain() {
        for (said in listOf("what's on Netflix tonight", "turn the TV off after this episode", "is the TV on", "play some music",
            "open the garage", "what's the weather", "remind me to watch Netflix at 8", "turn the volume to 250", "turn it up",
            "stop the car", "play some music", "go back to the last question"))
            assertEquals(said, "brain", s(said))
    }
}
