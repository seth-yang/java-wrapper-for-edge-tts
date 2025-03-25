package org.dreamwork.tools.tts;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

public class VoiceRoleAdapter extends TypeAdapter<VoiceRole> {
    @Override
    public void write (JsonWriter out, VoiceRole value) throws IOException {
        if (value == null) {
            out.nullValue ();
        } else {
            out.value (value.shortName);
        }
    }

    @Override
    public VoiceRole read (JsonReader in) throws IOException {
        String text = in.nextString ();
        if (text == null || text.trim ().isEmpty ()) {
            return null;
        } else {
            return VoiceRole.valueOf (text);
        }
    }
}