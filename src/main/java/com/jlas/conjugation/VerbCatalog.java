package com.jlas.conjugation;

import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class VerbCatalog {
    public record Verb(String id, String word, String reading, String meaning, int group, String level) {}
    public record Context(String id, String verbId, VerbEngine.Form form, String sentence, String translation) {}
    public record Data(List<Verb> verbs, List<Context> contexts) {}
    public record CatalogMetadata(java.time.Instant updatedAt, Long updatedBy) {}

    private final VerbPracticeCatalogVerbRepository verbRepository;
    private final VerbPracticeCatalogContextRepository contextRepository;
    private volatile Data data;
    private volatile Map<String, Verb> verbs = Map.of();
    private volatile Map<String, Context> contexts = Map.of();

    @Autowired
    public VerbCatalog(VerbPracticeCatalogVerbRepository verbRepository,
                       VerbPracticeCatalogContextRepository contextRepository) {
        this.verbRepository = verbRepository;
        this.contextRepository = contextRepository;
        reloadFromDatabase();
    }

    @org.springframework.transaction.annotation.Transactional
    public synchronized CatalogMetadata replace(Data candidate, Long updatedBy) {
        var validated = validate(candidate);
        var now = java.time.Instant.now();
        persist(validated, updatedBy, now);
        install(validated);
        return new CatalogMetadata(now, updatedBy);
    }

    public Data data() { ensureLoaded(); return data; }
    public CatalogMetadata metadata() {
        if (verbRepository == null || contextRepository == null) return new CatalogMetadata(null, null);
        var latestVerb = verbRepository.findAll().stream().max(Comparator.comparing(VerbPracticeCatalogVerb::getUpdatedAt)).orElse(null);
        var latestContext = contextRepository.findAll().stream().max(Comparator.comparing(VerbPracticeCatalogContext::getUpdatedAt)).orElse(null);
        if (latestVerb == null && latestContext == null) return new CatalogMetadata(null, null);
        if (latestContext == null || latestVerb != null && !latestContext.getUpdatedAt().isAfter(latestVerb.getUpdatedAt()))
            return new CatalogMetadata(latestVerb.getUpdatedAt(), latestVerb.getUpdatedBy());
        return new CatalogMetadata(latestContext.getUpdatedAt(), latestContext.getUpdatedBy());
    }
    public List<Verb> verbs() { ensureLoaded(); return data.verbs(); }
    public List<Context> contexts() { ensureLoaded(); return data.contexts(); }
    public Verb verb(String id) { ensureLoaded(); return Objects.requireNonNull(verbs.get(id), "Unknown verb"); }
    public Context context(String id) { ensureLoaded(); return id == null ? null : contexts.get(id); }

    private synchronized void ensureLoaded() {
        if (data == null || data.verbs().isEmpty()) reloadFromDatabase();
        if (data.verbs().isEmpty())
            throw new IllegalStateException("Verb practice catalog is empty. Run sql/seeds/seed_data_verb_practice.sql first.");
    }

    private synchronized void reloadFromDatabase() {
        var storedVerbs = verbRepository.findAllByOrderByIdAsc();
        if (storedVerbs.isEmpty()) {
            data = new Data(List.of(), List.of());
            verbs = Map.of();
            contexts = Map.of();
            return;
        }
        var storedContexts = contextRepository.findAllByOrderByIdAsc().stream()
                .map(row -> new Context(row.getId(), row.getVerbId(), VerbEngine.Form.valueOf(row.getFormCode()),
                        row.getSentence(), row.getTranslation()))
                .toList();
        install(new Data(storedVerbs.stream().map(VerbCatalog::toVerb).toList(), storedContexts));
    }

    private void persist(Data source, Long updatedBy, java.time.Instant updatedAt) {
        contextRepository.deleteAllInBatch();
        verbRepository.deleteAllInBatch();
        var verbRows = source.verbs().stream().map(v -> {
            var row = new VerbPracticeCatalogVerb();
            row.setId(v.id()); row.setWord(v.word()); row.setReading(v.reading()); row.setMeaning(v.meaning());
            row.setGroupNumber(v.group()); row.setLevel(v.level()); row.setUpdatedAt(updatedAt); row.setUpdatedBy(updatedBy);
            return row;
        }).toList();
        verbRepository.saveAllAndFlush(verbRows);
        var contextRows = source.contexts().stream().map(c -> {
            var row = new VerbPracticeCatalogContext();
            row.setId(c.id()); row.setVerbId(c.verbId()); row.setFormCode(c.form().name());
            row.setSentence(c.sentence()); row.setTranslation(c.translation()); row.setUpdatedAt(updatedAt); row.setUpdatedBy(updatedBy);
            return row;
        }).toList();
        contextRepository.saveAllAndFlush(contextRows);
    }

    private static Verb toVerb(VerbPracticeCatalogVerb row) {
        return new Verb(row.getId(), row.getWord(), row.getReading(), row.getMeaning(), row.getGroupNumber(), row.getLevel());
    }

    private void install(Data source) {
        var validated = validate(source);
        var verbMap = new LinkedHashMap<String, Verb>();
        validated.verbs().forEach(v -> verbMap.put(v.id(), v));
        var contextMap = new LinkedHashMap<String, Context>();
        validated.contexts().forEach(c -> contextMap.put(c.id(), c));
        this.data = validated;
        this.verbs = Collections.unmodifiableMap(verbMap);
        this.contexts = Collections.unmodifiableMap(contextMap);
    }

    private static Data validate(Data source) {
        if (source == null || source.verbs() == null || source.contexts() == null)
            throw new IllegalArgumentException("Danh sách động từ và câu ngữ cảnh không được để trống.");
        if (source.verbs().isEmpty()) throw new IllegalArgumentException("Cần có ít nhất một động từ.");
        if (source.verbs().size() > 5000 || source.contexts().size() > 20000)
            throw new IllegalArgumentException("Kho nội dung vượt quá giới hạn cho phép.");
        var verbMap = new LinkedHashMap<String, Verb>();
        var wordReadings = new HashSet<String>();
        for (var v : source.verbs()) {
            if (v == null || !validId(v.id()) || blank(v.word(), 80) || blank(v.reading(), 100)
                    || blank(v.meaning(), 240) || v.group() < 1 || v.group() > 3
                    || !Set.of("N5", "N4", "N3", "N2", "N1").contains(v.level()))
                throw new IllegalArgumentException("Thông tin động từ không hợp lệ: " + (v == null ? "" : v.id()));
            if (v.group() == 2 && !v.reading().endsWith("る"))
                throw new IllegalArgumentException("Động từ nhóm 2 phải kết thúc bằng る: " + v.word());
            if (v.group() == 3 && !(v.reading().endsWith("する") || v.reading().equals("くる")))
                throw new IllegalArgumentException("Động từ nhóm 3 phải là động từ する hoặc 来る: " + v.word());
            if (v.group() != 3 && !"うくぐすつぬぶむる".contains(v.reading().substring(v.reading().length() - 1)))
                throw new IllegalArgumentException("Đuôi đọc không phù hợp với động từ nhóm 1 hoặc 2: " + v.word());
            if (verbMap.put(v.id(), v) != null) throw new IllegalArgumentException("Trùng mã động từ: " + v.id());
            if (!wordReadings.add(v.word() + "\u0000" + v.reading()))
                throw new IllegalArgumentException("Trùng cách viết và cách đọc: " + v.word());
            try { for (var form : VerbEngine.Form.values()) if (VerbEngine.supports(v, form)) VerbEngine.conjugate(v, form, false); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Thông tin nhóm chia không hợp lệ: " + v.word()); }
        }
        var contextMap = new LinkedHashMap<String, Context>();
        for (var c : source.contexts()) {
            if (c == null || !validId(c.id()) || !verbMap.containsKey(c.verbId()) || c.form() == null
                    || blank(c.sentence(), 500) || countBlanks(c.sentence()) != 1
                    || blank(c.translation(), 500)
                    || (c.verbId() != null && c.form() != null && verbMap.containsKey(c.verbId())
                    && !VerbEngine.supports(verbMap.get(c.verbId()), c.form())))
                throw new IllegalArgumentException("Câu ngữ cảnh không hợp lệ: " + (c == null ? "" : c.id()));
            if (contextMap.put(c.id(), c) != null) throw new IllegalArgumentException("Trùng mã câu ngữ cảnh: " + c.id());
        }
        return new Data(List.copyOf(verbMap.values()), List.copyOf(contextMap.values()));
    }

    private static boolean validId(String value) { return value != null && value.matches("[a-z0-9][a-z0-9_-]{0,39}"); }
    private static boolean blank(String value, int max) { return value == null || value.isBlank() || value.length() > max; }
    private static long countBlanks(String value) { return value.split("___", -1).length - 1L; }

}
