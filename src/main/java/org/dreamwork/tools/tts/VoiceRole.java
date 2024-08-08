package org.dreamwork.tools.tts;

import java.util.*;
import java.util.stream.Collectors;

public enum VoiceRole {
    ///////////////////////////////  妹子们  ////////////////////////////////////
    /** 晓北 辽宁 东北大妹子 */
    Xiaobei ("zh-CN-liaoning-XiaobeiNeural", "female", "zh-CN-liaoning", "晓北"),

    /** 晓妮 陕西妹子 */
    Xiaoni ("zh-CN-shaanxi-XiaoniNeural", "female", "zh-CN-shaanxi", "晓妮"),

    /** 晓晓 活泼、温暖的声音，具有多种场景风格和情感 */
    Xiaoxiao ("zh-CN-XiaoxiaoNeural", "female", "zh-CN", "晓晓"),

    /** 晓萱 自信、有能力的声音，具有丰富的角色扮演和情感，适合音频书籍 */
    Xiaoxuan ("zh-CN-XiaoxuanNeural", "female", "zh-CN", "晓萱"),

    /** 晓伊 */ // <-- default
    Xiaoyi ("zh-CN-XiaoyiNeural", "female", "zh-CN", "晓伊"),

    /** 曉佳(HiuGaai),粤语female声 */
    HiuGaai ("zh-HK-HiuGaaiNeural", "female", "zh-HK", "曉佳"),

    /** 曉曼(HiuMaan),粤语female声 */
    HiuMaan ("zh-HK-HiuMaanNeural", "female", "zh-HK", "曉曼"),

    /**
     * 曉臻(HsiaoChen),湾湾female声
     */
    HsiaoChen ("zh-TW-HsiaoChenNeural", "female", "zh-TW", "曉臻"),

    /** 曉雨(HsiaoYu),湾湾female声 */
    HsiaoYu ("zh-TW-HsiaoYuNeural", "female", "zh-TW", "曉雨"),

    ////////////////////////////////// 汉子们 //////////////////////////////////////
    /** 云健 适合影视和体育解说 */
    Yunjian ("zh-CN-YunjianNeural", "male", "zh-CN", "云健"),

    /** 云希 活泼、阳光的声音，具有丰富的情感，可用于许多对话场景 */
    Yunxi ("zh-CN-YunxiNeural", "male", "zh-CN", "云希"),

    /** 云夏 少年年male声 */
    Yunxia ("zh-CN-YunxiaNeural", "male", "zh-CN", "云夏"),

    /** 云扬 专业、流利的声音，具有多种场景风格 */
    Yunyang ("zh-CN-YunyangNeural", "male", "zh-CN", "云扬"),

    /** 雲龍(WanLung),粤语male声 */
    WanLung ("zh-HK-WanLungNeural", "male", "zh-HK", "雲龍"),

    /** 雲哲(YunJhe),湾湾male声 */
    YunJhe ("zh-TW-YunJheNeural", "male", "zh-TW", "雲哲"),
    ;
    public final String shortName;
    public final String nickname;
    public final String gender;
    public final String locale;

    VoiceRole (String shortName, String gender, String locale, String nickname) {
        this.shortName = shortName;
        this.gender = gender;
        this.locale = locale;
        this.nickname = nickname;
    }

    /**
     * 根据指定的语言过滤
     * @param locale 指定的语言
     * @return 指定语言的语音列表
     */
    public static List<VoiceRole> byLocale (String locale) {
        final String lang = locale.toLowerCase ();
        return Arrays.stream (values ())
                .filter (v -> v.shortName.toLowerCase().startsWith (lang))
                .collect(Collectors.toList());
    }

    /**
     * 根据语音性别过滤
     * @param gender 指定的性别
     * @return 指定性别的语音列表
     */
    public static List<VoiceRole> byGender (String gender) {
        String g = gender.toLowerCase ();
        return Arrays.stream (values ()).filter (v -> v.gender.equals (g)).collect(Collectors.toList());
    }

    /**
     * 第一级按照语言分组，第二级按性别分组
     * @return 分组好的语音
     */
    public static Map<String, Map<String, List<VoiceRole>>> group () {
        Map<String, Map<String, List<VoiceRole>>> group = new HashMap<> ();
        Arrays.stream (values ()).forEach (v -> {
            Map<String, List<VoiceRole>> map = group.computeIfAbsent (v.locale, k -> new HashMap<> ());
            map.computeIfAbsent (v.gender, k -> new ArrayList<> ()).add (v);
        });
        return group;
    }
}