package com.jlas.conjugation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.jlas.conjugation.VerbEngine.Form;

@Service @RequiredArgsConstructor
@Transactional
public class VerbPracticeService {
    public enum Mode { CHOICE, INPUT, CHAIN, CONTEXT, SPEED, REVIEW }
    public record Settings(Mode mode, List<Form> forms, Integer group, String level, int count) {}
    public record Prompt(String id,String verbId,Form form,String contextId,List<String> choices) {}
    public record Answer(String questionId,String verb,String reading,String form,String supplied,String expected,String expectedReading,boolean correct,String explanation,long answeredAt) {}
    public record Payload(Settings settings,List<Prompt> questions,List<Answer> answers) {}
    public record Question(String id,String word,String reading,String meaning,int group,String level,Form form,String formLabel,String sentence,String translation,List<String> choices) {}
    public record SessionView(String id,Mode mode,int total,int answered,int correct,Instant serverNow,Instant deadline,boolean finished,Question question,Answer feedback,List<Answer> results,Settings settings) {}
    public record FormInfo(Form id,String label,String description,boolean advanced,long attempts,long correct) {}
    public record Recent(String id,String mode,int answered,int correct,Instant startedAt,boolean finished) {}
    public record LevelInfo(String id,long verbCount,long contextCount) {}
    public record Dashboard(int verbCount,long attempted,long correct,long mistakes,long today,int speedRecord,List<FormInfo> forms,List<Recent> recent,SessionView active,List<LevelInfo> levels) {}
    private final VerbCatalog catalog;
    private final VerbSessionRepository sessions;
    private final VerbProgressRepository progress;
    private final UserRepository users;
    private final ObjectMapper mapper=new ObjectMapper();
    Clock clock=Clock.systemUTC();

    private void lock(Long user) {
        if(user==null) throw new ApiException(ErrorCode.UNAUTHORIZED);
        users.findForCreditUpdate(user).orElseThrow(()->new ApiException(ErrorCode.UNAUTHORIZED));
    }
    public Dashboard dashboard(Long user) {
        lock(user);
        var rows=progress.findByUserId(user);
        var forms=Arrays.stream(Form.values()).map(f->new FormInfo(f,f.label,f.description,f.advanced,
                rows.stream().filter(r->r.getFormCode().equals(f.name())).mapToLong(VerbPracticeProgress::getAttempts).sum(),
                rows.stream().filter(r->r.getFormCode().equals(f.name())).mapToLong(VerbPracticeProgress::getCorrectCount).sum())).toList();
        SessionView active=null;
        for(var s:sessions.findByUserIdAndFinishedAtIsNull(user)) {
            var view=view(s,read(s),null);
            if(!view.finished()) active=view;
        }
        var since=LocalDate.now(clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh"))).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        return new Dashboard(catalog.verbs().size(),rows.stream().mapToLong(VerbPracticeProgress::getAttempts).sum(),
                rows.stream().mapToLong(VerbPracticeProgress::getCorrectCount).sum(),rows.stream().filter(VerbPracticeProgress::isNeedsReview).count(),
                sessions.forDailyProgress(user,since).stream().flatMap(s->read(s).answers().stream()).filter(a->a.answeredAt()>=since.toEpochMilli()).count(),sessions.speedRecord(user),forms,
                sessions.findTop5ByUserIdOrderByStartedAtDesc(user).stream().map(s->new Recent(s.getId(),s.getMode(),s.getAnsweredCount(),s.getCorrectCount(),s.getStartedAt(),s.getFinishedAt()!=null)).toList(),active,
                List.of("N5","N4","N3","N2","N1").stream().map(level->new LevelInfo(level,
                        catalog.verbs().stream().filter(v->v.level().equals(level)).count(),
                        catalog.contexts().stream().filter(c->catalog.verb(c.verbId()).level().equals(level)).count())).toList());
    }
    public SessionView start(Long user,Settings settings) {
        lock(user);
        if(settings==null||settings.mode()==null||settings.forms()==null||settings.forms().isEmpty()||settings.forms().stream().anyMatch(Objects::isNull)||settings.forms().size()>12
                ||settings.count()<5||settings.count()>30||settings.group()==null||settings.group()<0||settings.group()>3
                ||!Set.of("ALL","N5","N4","N3","N2","N1").contains(Objects.toString(settings.level(),"")))
            throw new ApiException(ErrorCode.BAD_REQUEST,"Hãy chọn chế độ, ít nhất một thể và số lượt từ 5 đến 30.");
        var verbs=catalog.verbs().stream().filter(v->(settings.group()==0||v.group()==settings.group())&&(settings.level().equals("ALL")||v.level().equals(settings.level()))).toList();
        var forms=settings.forms().stream().distinct().toList();
        var candidates=new ArrayList<Prompt>();
        if(settings.mode()==Mode.CONTEXT) {
            for(var c:catalog.contexts()) if(forms.contains(c.form())&&verbs.stream().anyMatch(v->v.id().equals(c.verbId()))) candidates.add(prompt(c.verbId(),c.form(),c.id(),false));
        } else if(settings.mode()==Mode.REVIEW) {
            for(var r:progress.findByUserId(user)) if(r.isNeedsReview()&&forms.contains(Form.valueOf(r.getFormCode()))&&verbs.stream().anyMatch(v->v.id().equals(r.getVerbId())))
                candidates.add(prompt(r.getVerbId(),Form.valueOf(r.getFormCode()),null,false));
        } else {
            var shuffled=new ArrayList<>(verbs); Collections.shuffle(shuffled);
            for(var v:shuffled) for(var f:forms) if(VerbEngine.supports(v,f)) candidates.add(prompt(v.id(),f,null,settings.mode()==Mode.CHOICE));
        }
        if(candidates.isEmpty()) throw new ApiException(ErrorCode.BAD_REQUEST,settings.mode()==Mode.REVIEW?"Không có lỗi sai phù hợp với bộ lọc này. Thử chọn tất cả nhóm và thể.":"Chưa có câu phù hợp. Hãy mở rộng nhóm động từ hoặc thể cần luyện.");
        if(settings.mode()!=Mode.CHAIN) Collections.shuffle(candidates);
        int wanted=settings.mode()==Mode.SPEED?200:Math.min(settings.count(),candidates.size());
        var questions=new ArrayList<Prompt>();
        for(int i=0;i<wanted;i++){var q=candidates.get(i%candidates.size()); questions.add(new Prompt(UUID.randomUUID().toString(),q.verbId(),q.form(),q.contextId(),q.choices()));}
        // One active round per account; lock the account for concurrent tabs/devices.
        for(var old:sessions.findByUserIdAndFinishedAtIsNull(user)) old.setFinishedAt(clock.instant());
        var s=new VerbPracticeSession();s.setId(UUID.randomUUID().toString());s.setUserId(user);s.setMode(settings.mode().name());s.setStartedAt(clock.instant());
        if(settings.mode()==Mode.SPEED)s.setDeadlineAt(clock.instant().plusSeconds(60));
        var payload=new Payload(settings,questions,new ArrayList<>());write(s,payload);sessions.save(s);
        return view(s,payload,null);
    }
    private Prompt prompt(String verbId,Form f,String context,boolean choice) {
        var options=new ArrayList<String>();var v=catalog.verb(verbId);
        if(choice){String answer=VerbEngine.conjugate(v,f,false);options.add(answer);
            var forms=new ArrayList<>(List.of(Form.values()));Collections.shuffle(forms);
            for(var other:forms) if(VerbEngine.supports(v,other)) {String alt=VerbEngine.conjugate(v,other,false);if(!VerbEngine.correct(v,f,alt)&&!options.contains(alt))options.add(alt);if(options.size()==4)break;}
            Collections.shuffle(options);
        }
        return new Prompt(UUID.randomUUID().toString(),verbId,f,context,options);
    }
    public SessionView get(Long user,String id) {lock(user);var s=owned(user,id);return view(s,read(s),null);}
    public SessionView answer(Long user,String id,String questionId,String value,boolean skip) {
        lock(user);var s=owned(user,id);var p=read(s);
        // Retries after a lost response return the original grade without double-counting.
        var previous=p.answers().stream().filter(a->a.questionId().equals(questionId)).findFirst();
        if(previous.isPresent())return view(s,p,previous.get());
        if(expired(s)||s.getFinishedAt()!=null)return view(s,p,null);
        if(value==null||value.length()>120)throw new ApiException(ErrorCode.BAD_REQUEST,"Đáp án không hợp lệ.");
        if(!skip&&value.isBlank())throw new ApiException(ErrorCode.BAD_REQUEST,"Nhập hoặc chọn đáp án trước khi kiểm tra.");
        var q=p.questions().get(s.getAnsweredCount());
        if(!q.id().equals(questionId))throw new ApiException(ErrorCode.CONFLICT,"Câu hỏi đã thay đổi. Hãy tải lại vòng luyện.");
        var v=catalog.verb(q.verbId());boolean correct=!skip&&VerbEngine.correct(v,q.form(),value);
        var a=new Answer(q.id(),v.word(),v.reading(),q.form().label,skip?"(Bỏ qua)":value,VerbEngine.conjugate(v,q.form(),false),VerbEngine.conjugate(v,q.form(),true),correct,VerbEngine.explanation(v,q.form()),clock.millis());
        p.answers().add(a);s.setAnsweredCount(s.getAnsweredCount()+1);if(correct)s.setCorrectCount(s.getCorrectCount()+1);
        var r=progress.findByUserIdAndVerbIdAndFormCode(user,v.id(),q.form().name()).orElseGet(()->{var n=new VerbPracticeProgress();n.setUserId(user);n.setVerbId(v.id());n.setFormCode(q.form().name());return n;});
        r.setAttempts(r.getAttempts()+1);if(correct)r.setCorrectCount(r.getCorrectCount()+1);r.setNeedsReview(!correct);r.setUpdatedAt(clock.instant());progress.save(r);
        if(s.getAnsweredCount()==p.questions().size())s.setFinishedAt(clock.instant());
        write(s,p);return view(s,p,a);
    }
    public SessionView finish(Long user,String id) {lock(user);var s=owned(user,id);if(s.getFinishedAt()==null)s.setFinishedAt(clock.instant());return view(s,read(s),null);}
    private boolean expired(VerbPracticeSession s) {
        if(s.getFinishedAt()==null&&s.getDeadlineAt()!=null&&!clock.instant().isBefore(s.getDeadlineAt()))s.setFinishedAt(s.getDeadlineAt());
        return s.getFinishedAt()!=null;
    }
    private SessionView view(VerbPracticeSession s,Payload p,Answer feedback) {
        boolean done=expired(s);Question question=null;
        if(!done&&s.getAnsweredCount()<p.questions().size()){
            var q=p.questions().get(s.getAnsweredCount());var v=catalog.verb(q.verbId());var c=catalog.context(q.contextId());
            question=new Question(q.id(),v.word(),v.reading(),v.meaning(),v.group(),v.level(),q.form(),q.form().label,c==null?null:c.sentence(),c==null?null:c.translation(),q.choices());
        }
        return new SessionView(s.getId(),Mode.valueOf(s.getMode()),p.questions().size(),s.getAnsweredCount(),s.getCorrectCount(),clock.instant(),s.getDeadlineAt(),done,question,feedback,done?List.copyOf(p.answers()):List.of(),p.settings());
    }
    private VerbPracticeSession owned(Long user,String id){return sessions.findByIdAndUserId(id,user).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND,"Không tìm thấy vòng luyện của bạn."));}
    private Payload read(VerbPracticeSession s){try{return mapper.readValue(s.getPayload(),Payload.class);}catch(Exception e){throw new IllegalStateException("Cannot read practice session",e);}}
    private void write(VerbPracticeSession s,Payload p){try{s.setPayload(mapper.writeValueAsString(p));}catch(Exception e){throw new IllegalStateException("Cannot save practice session",e);}}
}
