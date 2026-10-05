package com.jlas.kaiwa.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.kaiwa.model.*;
import java.util.*;

public final class StageFlowSupport {
    private static final ObjectMapper JSON=new ObjectMapper();
    private StageFlowSupport() {}
    public static ScenarioStage stage(ConversationSession session) {
        if(session.getStageCode()==null)return null;
        try { return JSON.readValue(session.getStageDefinitionJson(),ScenarioStage.class); }
        catch(Exception e){throw new IllegalStateException("Invalid saved stage definition",e);}
    }
    public static boolean permits(ConversationSession session,String source,String target) {
        var stage=stage(session);if(stage==null)return true;
        var route=stage.stateCodes();int cursor=session.getStageCursor();
        if(cursor<0||cursor>=route.size()||!route.get(cursor).equals(source))return false;
        if(cursor+1<route.size()&&route.get(cursor+1).equals(target))return true;
        // A genuine graph return can revisit an earlier beat of this stage.
        return route.subList(0,cursor).contains(target);
    }
    public static void advance(ConversationSession session,String target) {
        var stage=stage(session);if(stage==null)return;
        var route=stage.stateCodes();int cursor=session.getStageCursor();
        if(cursor+1<route.size()&&route.get(cursor+1).equals(target)){session.setStageCursor(cursor+1);return;}
        for(int i=cursor-1;i>=0;i--)if(route.get(i).equals(target)){session.setStageCursor(i);return;}
        throw new IllegalStateException("Target outside stage route");
    }
    public static boolean atEnd(ConversationSession session) {
        var stage=stage(session);
        return stage!=null&&session.getStageCursor()==stage.stateCodes().size()-1;
    }
}
