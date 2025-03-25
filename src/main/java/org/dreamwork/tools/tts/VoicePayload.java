package org.dreamwork.tools.tts;

import com.google.gson.annotations.JsonAdapter;

import java.io.Serializable;

public class VoicePayload implements Serializable {
    public static final String model = "tts-1";

    public String input;

    @JsonAdapter (VoiceRoleAdapter.class)
    public VoiceRole voice;

    public VoicePayload () {
    }

    public VoicePayload (String input, VoiceRole voice) {
        this.input = input;
        this.voice = voice;
    }
}