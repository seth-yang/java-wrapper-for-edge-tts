package org.dreamwork.tools.tts;

import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.dreamwork.tools.tts.Const.*;

public class CommandLineHelper {
    /** command line argument parser */
    private static final Pattern P = Pattern.compile ("^--(.*?)=(.*?)$");

    public static Properties loadFromCommandLineArgs (String... args) {
        Properties props = null;
        String endpoint = null, apiKey = null, proxy = null, user = null, password = null, part;
        for (int i = 0; i < args.length; i ++) {
            part = args[i];
            if (part.startsWith ("--")) {
                Matcher m = P.matcher (part.trim ());
                if (m.matches ()) {
                    String option = m.group (1);
                    String value  = m.group (2);
                    switch (option) {
                        case "endpoint":
                            endpoint = value.trim ();
                            break;

                        case "api-key":
                            apiKey = value.trim ();
                            break;

                        case "proxy-server":
                            proxy = value.trim ();
                            break;

                        case "proxy-user":
                            user = value.trim ();
                            break;

                        case "proxy-password":
                            password = value.trim ();
                            break;

                        default:
                            throw new RuntimeException ("unknown option: " + part);
                    }
                }
            } else {
                i ++;
                String value = args [i];
                switch (part) {
                    case "-e":
                        endpoint = value.trim ();
                        break;

                    case "-k":
                        apiKey = value.trim ();
                        break;

                    default:
                        throw new RuntimeException ("unknown option: " + part);
                }
            }
        }

        if (endpoint != null && apiKey != null) {
            props = new Properties ();
            props.setProperty (KEY_ENDPOINT, endpoint.trim ());
            props.setProperty (KEY_API_KEY, apiKey.trim ());

            if (proxy != null) {
                String[] temp = proxy.split (":");
                if (isNotEmpty (temp[0])) {
                    props.setProperty (KEY_PROXY_ENABLED, "true");
                    props.setProperty (KEY_PROXY_SERVER, temp[0]);
                    if (isNotEmpty (temp[1])) {
                        props.setProperty (KEY_PROXY_PORT, temp[1].trim ());
                    }
                }

                if (isNotEmpty (user)) {
                    props.setProperty (KEY_PROXY_USER, user.trim ());
                    if (isNotEmpty (password)) {
                        props.setProperty (KEY_PROXY_PASSWORD, password.trim ());
                    }
                }
            }
        }

        return props;
    }

    public static boolean isNotEmpty (String text) {
        return text != null && !text.trim ().isEmpty ();
    }
}