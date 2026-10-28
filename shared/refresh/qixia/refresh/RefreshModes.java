package qixia.refresh;

import java.util.*;
import java.util.regex.*;

/** 物理 ID 与 framework ID 分开保存，绝不把逻辑 ID 直接送入 SurfaceFlinger。 */
public final class RefreshModes {
    public static final class Mode {
        public final int id, width, height;
        public final float hz;
        public final boolean physical;
        public Mode(int id, int width, int height, float hz, boolean physical) {
            this.id=id; this.width=width; this.height=height; this.hz=hz; this.physical=physical;
        }
    }
    public static final class Snapshot {
        public final List<Mode> modes;
        public final float active;
        Snapshot(List<Mode> modes, float active) { this.modes=modes; this.active=active; }
    }
    private static String field(String raw, String name, String pattern) {
        Matcher m=Pattern.compile("\\b"+name+"\\s*=\\s*("+pattern+")").matcher(raw);
        return m.find()?m.group(1):"";
    }
    private static Mode parseMode(String raw, boolean physical) {
        try {
            int id=Integer.parseInt(field(raw,"id","[0-9]+"));
            String w=field(raw,"width","[0-9]+"), h=field(raw,"height","[0-9]+");
            if (w.isEmpty()) {
                Matcher m=Pattern.compile("resolution\\s*=\\s*(\\d+)\\s*x\\s*(\\d+)").matcher(raw);
                if(!m.find()) return null; w=m.group(1); h=m.group(2);
            }
            String rate=field(raw,"vsyncRate","[0-9.]+");
            if(rate.isEmpty()) rate=field(raw,physical?"refreshRate":"fps","[0-9.]+");
            int width=Integer.parseInt(w), height=Integer.parseInt(h); float hz=Float.parseFloat(rate);
            return width>0 && height>0 && Float.isFinite(hz) && hz>0 ? new Mode(id,width,height,hz,physical):null;
        } catch(RuntimeException e) { return null; }
    }
    public static Snapshot surface(String dump, long physicalId, boolean single) {
        Matcher headers=Pattern.compile("(?m)^Display (\\d+)\\s*$").matcher(dump);
        List<Integer> starts=new ArrayList<>(), ends=new ArrayList<>(); List<String> ids=new ArrayList<>();
        while(headers.find()) { starts.add(headers.start());ends.add(headers.end());ids.add(headers.group(1)); }
        for(int i=0;i<starts.size();i++) {
            if(!ids.get(i).equals(Long.toString(physicalId)) && !(physicalId<0 && single && starts.size()==1)) continue;
            String section=dump.substring(ends.get(i),i+1<starts.size()?starts.get(i+1):dump.length());
            List<Mode> modes=new ArrayList<>();float active=Float.NaN;boolean inModes=false;
            for(String line:section.split("\\r?\\n")) {
                String s=line.trim();
                if(s.startsWith("activeMode=")) { Mode m=parseMode(s,true);if(m!=null)active=m.hz; }
                if(s.startsWith("displayModes=")) { inModes=true;continue; }
                if(inModes) { Mode m=parseMode(s,true);if(m!=null)modes.add(m);else if(!s.isEmpty())inModes=false; }
            }
            return new Snapshot(modes,active);
        }
        return new Snapshot(Collections.emptyList(),Float.NaN);
    }
    public static List<Mode> display(String dump, boolean single) {
        List<Mode> result=new ArrayList<>();
        // 旧版物理列表缺少可靠屏幕标识，多屏时不从整份 dump 猜主屏。
        if(single) {
            int start=dump.indexOf("mSfDisplayModes=");
            if(start>=0 && dump.indexOf("mSfDisplayModes=",start+1)<0) {
                String rest=dump.substring(start+"mSfDisplayModes=".length());
                for(String line:rest.split("\\r?\\n")) {
                    if(line.trim().isEmpty())continue;
                    if(!line.trim().startsWith("DisplayMode{"))break;
                    Mode m=parseMode(line,true);if(m!=null)result.add(m);
                }
            }
        }
        if(!result.isEmpty())return result;
        // 最后一层只能补充 Hz 选项，不提供物理控制 ID。
        int start=dump.indexOf("mSupportedModesByDisplay:");
        if(start>=0) {
            Matcher line=Pattern.compile("(?m)^\\s*0\\s*->\\s*(.*)$").matcher(dump.substring(start));
            if(line.find()) {
                Matcher mode=Pattern.compile("\\{id=[^}]+}").matcher(line.group(1));
                while(mode.find()) { Mode m=parseMode(mode.group(),false);if(m!=null)result.add(m); }
            }
        }
        return result;
    }
    public static int select(List<Mode> modes, int width, int height, float hz, String custom) {
        int id=-1;float distance=.6f;
        for(Mode m:modes) if(m.physical && m.width==width && m.height==height && Math.abs(m.hz-hz)<distance
                && (custom.isEmpty() || custom.equals(Integer.toString(m.id)))) { id=m.id;distance=Math.abs(m.hz-hz); }
        return id;
    }
    public static Boolean overrideState(String dump) {
        boolean yes=dump.contains("(mode override by backdoor: yes)"), no=dump.contains("(mode override by backdoor: no)");
        Matcher m=Pattern.compile("(?m)^\\s*(?:mDebugDisplayModeSetByBackdoor|debugDisplayModeSetByBackdoor)\\s*=\\s*(true|false)\\s*$").matcher(dump);
        while(m.find()) { if(m.group(1).equals("true"))yes=true;else no=true; }
        return yes==no ? null : yes;
    }
}
