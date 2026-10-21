package com.jlas.conjugation;

import java.text.Normalizer;
import java.util.*;
import static com.jlas.conjugation.VerbCatalog.Verb;

/** Standard classroom forms. Kanji, kana, and romaji answers are accepted; contracted/ra-dropped forms are not taught here. */
public final class VerbEngine {
    private VerbEngine() {}
    public enum Form {
        MASU("Thể ます", "Lịch sự", false), TE("Thể て", "Nối hành động", false),
        NAI("Thể ない", "Phủ định", false), TA("Thể た", "Quá khứ", false),
        NAKATTA("Thể なかった", "Phủ định quá khứ", false),
        POTENTIAL("Khả năng", "Có thể làm", true), VOLITIONAL("Ý chí", "Cùng / định làm", true),
        BA("Điều kiện ば", "Nếu…", true), TARA("Điều kiện たら", "Nếu / sau khi…", true),
        PASSIVE("Bị động", "Được / bị…", true), CAUSATIVE("Sai khiến", "Cho / bắt làm", true),
        CAUSATIVE_PASSIVE("Sai khiến bị động", "Bị bắt làm", true);
        public final String label, description; public final boolean advanced;
        Form(String label, String description, boolean advanced) { this.label=label; this.description=description; this.advanced=advanced; }
    }
    private static final Map<String,String> ROMAJI = romajiMap();

    private static Map<String,String> romajiMap() {
        var map = new HashMap<String,String>();
        addRow(map,"","あいうえお"); addRow(map,"k","かきくけこ"); addRow(map,"s","さしすせそ");
        addRow(map,"t","たちつてと"); addRow(map,"n","なにぬねの"); addRow(map,"h","はひふへほ");
        addRow(map,"m","まみむめも"); addRow(map,"y","や-ゆ-よ"); addRow(map,"r","らりるれろ");
        addRow(map,"w","わ---を"); addRow(map,"g","がぎぐげご"); addRow(map,"z","ざじずぜぞ");
        addRow(map,"d","だぢづでど"); addRow(map,"b","ばびぶべぼ"); addRow(map,"p","ぱぴぷぺぽ");
        map.putAll(Map.ofEntries(
                Map.entry("shi","し"),Map.entry("chi","ち"),Map.entry("tsu","つ"),Map.entry("fu","ふ"),
                Map.entry("ji","じ"),Map.entry("si","し"),Map.entry("ti","ち"),Map.entry("tu","つ"),
                Map.entry("hu","ふ"),Map.entry("zi","じ"),Map.entry("di","ぢ"),Map.entry("du","づ"),
                Map.entry("she","しぇ"),Map.entry("che","ちぇ"),Map.entry("tsa","つぁ"),Map.entry("tsi","つぃ"),
                Map.entry("tse","つぇ"),Map.entry("tso","つぉ"),Map.entry("tcha","っちゃ"),Map.entry("tchi","っち"),
                Map.entry("tchu","っちゅ"),Map.entry("tche","っちぇ"),Map.entry("tcho","っちょ"),
                Map.entry("fa","ふぁ"),Map.entry("fi","ふぃ"),Map.entry("fe","ふぇ"),Map.entry("fo","ふぉ"),
                Map.entry("va","ゔぁ"),Map.entry("vi","ゔぃ"),Map.entry("vu","ゔ"),Map.entry("ve","ゔぇ"),Map.entry("vo","ゔぉ"),
                Map.entry("wi","うぃ"),Map.entry("we","うぇ"),Map.entry("ye","いぇ"),
                Map.entry("xtsu","っ"),Map.entry("ltsu","っ"),Map.entry("xtu","っ"),Map.entry("ltu","っ")
        ));
        addSmallKana(map,"ky","き"); addSmallKana(map,"gy","ぎ"); addSmallKana(map,"sh","し");
        addSmallKana(map,"sy","し"); addSmallKana(map,"j","じ"); addSmallKana(map,"zy","じ");
        addSmallKana(map,"ch","ち"); addSmallKana(map,"ty","ち"); addSmallKana(map,"dy","ぢ");
        addSmallKana(map,"ny","に"); addSmallKana(map,"hy","ひ"); addSmallKana(map,"by","び");
        addSmallKana(map,"py","ぴ"); addSmallKana(map,"my","み"); addSmallKana(map,"ry","り");
        map.putAll(Map.ofEntries(Map.entry("xa","ぁ"),Map.entry("xi","ぃ"),Map.entry("xu","ぅ"),Map.entry("xe","ぇ"),Map.entry("xo","ぉ"),
                Map.entry("xya","ゃ"),Map.entry("xyu","ゅ"),Map.entry("xyo","ょ"),Map.entry("xwa","ゎ"),
                Map.entry("la","ぁ"),Map.entry("li","ぃ"),Map.entry("lu","ぅ"),Map.entry("le","ぇ"),Map.entry("lo","ぉ"),
                Map.entry("lya","ゃ"),Map.entry("lyu","ゅ"),Map.entry("lyo","ょ"),Map.entry("lwa","ゎ")));
        return Map.copyOf(map);
    }

    private static void addRow(Map<String,String> map, String prefix, String kana) {
        String vowels="aiueo";
        for(int i=0;i<5;i++) if(kana.charAt(i)!='-') map.put(prefix+vowels.charAt(i),String.valueOf(kana.charAt(i)));
    }

    private static void addSmallKana(Map<String,String> map, String prefix, String base) {
        map.put(prefix+"a",base+"ゃ"); map.put(prefix+"u",base+"ゅ"); map.put(prefix+"o",base+"ょ");
    }
    public static boolean supports(Verb v, Form f) {
        if (v.id().equals("wakaru") && f==Form.POTENTIAL) return false;
        if (v.id().equals("dekiru") && Set.of(Form.POTENTIAL,Form.PASSIVE,Form.CAUSATIVE,Form.CAUSATIVE_PASSIVE,Form.VOLITIONAL).contains(f)) return false;
        return !v.id().equals("aru") || Set.of(Form.MASU,Form.TE,Form.NAI,Form.TA,Form.NAKATTA,Form.BA,Form.TARA).contains(f);
    }
    public static String conjugate(Verb v, Form f, boolean kana) {
        if (!supports(v,f)) throw new IllegalArgumentException("Unsupported conjugation");
        String word=kana?v.reading():v.word();
        if (f==Form.NAKATTA) { String n=conjugate(v,Form.NAI,kana); return n.substring(0,n.length()-1)+"かった"; }
        if (f==Form.TARA) return conjugate(v,Form.TA,kana)+"ら";
        if (v.group()==3) {
            if (v.reading().endsWith("する")) {
                String stem=word.substring(0,word.length()-2);
                return stem+switch(f) {
                    case MASU -> "します"; case TE -> "して"; case NAI -> "しない"; case TA -> "した";
                    case POTENTIAL -> "できる"; case VOLITIONAL -> "しよう"; case BA -> "すれば";
                    case PASSIVE -> "される"; case CAUSATIVE -> "させる"; case CAUSATIVE_PASSIVE -> "させられる";
                    default -> throw new IllegalArgumentException();
                };
            }
            if (v.reading().equals("くる")) return switch(f) {
                case MASU -> kana?"きます":"来ます"; case TE -> kana?"きて":"来て";
                case NAI -> kana?"こない":"来ない"; case TA -> kana?"きた":"来た";
                case POTENTIAL, PASSIVE -> kana?"こられる":"来られる";
                case VOLITIONAL -> kana?"こよう":"来よう"; case BA -> kana?"くれば":"来れば";
                case CAUSATIVE -> kana?"こさせる":"来させる";
                case CAUSATIVE_PASSIVE -> kana?"こさせられる":"来させられる";
                default -> throw new IllegalArgumentException();
            };
            throw new IllegalArgumentException("Unknown irregular verb");
        }
        String stem=word.substring(0,word.length()-1);
        if (v.group()==2) return stem+switch(f) {
            case MASU -> "ます"; case TE -> "て"; case NAI -> "ない"; case TA -> "た";
            case POTENTIAL, PASSIVE -> "られる"; case VOLITIONAL -> "よう"; case BA -> "れば";
            case CAUSATIVE -> "させる"; case CAUSATIVE_PASSIVE -> "させられる";
            default -> throw new IllegalArgumentException();
        };
        char end=word.charAt(word.length()-1);
        int i="うくぐすつぬぶむる".indexOf(end);
        if (i<0) throw new IllegalArgumentException("Unknown godan ending");
        String a=stem+"わかがさたなばまら".charAt(i), e=stem+"えけげせてねべめれ".charAt(i);
        return switch(f) {
            case MASU -> stem+"いきぎしちにびみり".charAt(i)+"ます";
            case NAI -> v.id().equals("aru")?"ない":a+"ない";
            case POTENTIAL -> e+"る"; case VOLITIONAL -> stem+"おこごそとのぼもろ".charAt(i)+"う";
            case BA -> e+"ば"; case PASSIVE -> a+"れる"; case CAUSATIVE -> a+"せる";
            case CAUSATIVE_PASSIVE -> a+"せられる";
            case TE, TA -> {
                String tail=v.id().equals("iku")?"って":switch(end) {
                    case 'う','つ','る' -> "って"; case 'む','ぶ','ぬ' -> "んで";
                    case 'く' -> "いて"; case 'ぐ' -> "いで"; case 'す' -> "して";
                    default -> throw new IllegalArgumentException();
                };
                if (f==Form.TA) tail=tail.replace('て','た').replace('で','だ');
                yield stem+tail;
            }
            default -> throw new IllegalArgumentException();
        };
    }
    public static Set<String> answers(Verb v, Form f) {
        var answers=new LinkedHashSet<>(List.of(conjugate(v,f,false),conjugate(v,f,true)));
        // The shortened godan causative-passive is standard, except verbs ending in す.
        if (f==Form.CAUSATIVE_PASSIVE && v.group()==1 && !v.reading().endsWith("す")) {
            for (boolean kana : new boolean[]{false,true}) answers.add(conjugate(v,f,kana).replaceFirst("せられる$","される"));
        }
        return answers;
    }
    public static String normalize(String value) {
        String s=Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).strip();
        String romaji=toHiraganaRomaji(s.toLowerCase(Locale.ROOT));
        if(romaji!=null) return romaji;
        var out=new StringBuilder();
        s.codePoints().forEach(c -> out.appendCodePoint(c>=0x30a1&&c<=0x30f6?c-0x60:c));
        return out.toString();
    }

    private static String toHiraganaRomaji(String value) {
        if(value.isEmpty()||!value.matches("[a-z']+")) return null;
        var out=new StringBuilder();
        for(int i=0;i<value.length();) {
            String match=null;
            int matchedLength=0;
            for(int length=Math.min(4,value.length()-i);length>=1;length--) {
                match=ROMAJI.get(value.substring(i,i+length));
                if(match!=null) { matchedLength=length; break; }
            }
            if(match!=null) {
                out.append(match);
                i+=matchedLength;
                continue;
            }
            char current=value.charAt(i);
            if(current=='n') {
                if(i+1<value.length()&&value.charAt(i+1)=='\'') { out.append('ん'); i+=2; continue; }
                if(i+1==value.length()||!isVowel(value.charAt(i+1))&&value.charAt(i+1)!='y') {
                    out.append('ん');
                    if(i+1<value.length()&&value.charAt(i+1)=='n'&&(i+2==value.length()||!isVowel(value.charAt(i+2)))) i+=2;
                    else i++;
                    continue;
                }
            }
            if(i+1<value.length()&&current==value.charAt(i+1)&&isConsonant(current)&&current!='n') {
                out.append('っ'); i++; continue;
            }
            return null;
        }
        return out.toString();
    }

    private static boolean isVowel(char c) { return "aiueo".indexOf(c)>=0; }
    private static boolean isConsonant(char c) { return c>='a'&&c<='z'&&!isVowel(c); }
    public static boolean correct(Verb v, Form f, String value) {
        return answers(v,f).stream().map(VerbEngine::normalize).anyMatch(normalize(value)::equals);
    }
    public static String explanation(Verb v, Form f) {
        String result=conjugate(v,f,false), rule;
        if (v.id().equals("iku") && Set.of(Form.TE,Form.TA,Form.TARA).contains(f)) rule="行く là ngoại lệ: 行って / 行った, không dùng 行いて.";
        else if (v.id().equals("aru") && Set.of(Form.NAI,Form.NAKATTA).contains(f)) rule="ある có phủ định đặc biệt là ない; quá khứ phủ định là なかった.";
        else if (v.group()==3) rule=v.reading().endsWith("する")?"Động từ nhóm 3: chia phần する; thể khả năng là できる.":"来る thuộc nhóm 3. Cách đọc thay đổi thành き, こ hoặc く tùy thể.";
        else if (v.group()==2) rule="Nhóm 2: bỏ る rồi thêm đuôi của thể cần chia.";
        else if (f==Form.TE||f==Form.TA||f==Form.TARA) rule="Nhóm 1: う・つ・る → って; む・ぶ・ぬ → んで; く → いて; ぐ → いで; す → して. Thể た đổi て/で thành た/だ.";
        else rule=switch(f) {
            case MASU -> "Nhóm 1: đổi âm cuối sang hàng い rồi thêm ます.";
            case NAI, NAKATTA -> "Nhóm 1: đổi âm cuối sang hàng あ rồi thêm ない; riêng う → わない. Quá khứ: ない → なかった.";
            case POTENTIAL -> "Nhóm 1: đổi âm cuối sang hàng え rồi thêm る.";
            case VOLITIONAL -> "Nhóm 1: đổi âm cuối sang hàng お rồi thêm う.";
            case BA -> "Nhóm 1: đổi âm cuối sang hàng え rồi thêm ば.";
            case PASSIVE -> "Nhóm 1: đổi âm cuối sang hàng あ rồi thêm れる; riêng う dùng わ.";
            case CAUSATIVE -> "Nhóm 1: đổi âm cuối sang hàng あ rồi thêm せる; riêng う dùng わ.";
            case CAUSATIVE_PASSIVE -> "Nhóm 1: hàng あ + せられる. Có thể rút gọn thành hàng あ + される, trừ động từ kết thúc bằng す.";
            default -> "";
        };
        return v.word()+" → "+result+". "+rule;
    }
}
