package com.jlas.kaiwa.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured JSON the AI must return so Scenario Engine can evaluate progress.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiModelReply {
    private String reply;
    private boolean hasError;
    private String originalWrong;
    private String corrected;
    private String grammarReason;
    private String mistakeType;
    private List<String> objectivesMet = new ArrayList<>();
    private boolean readyToAdvanceState;
    private boolean readyToEnd;
    private List<String> expressions = new ArrayList<>();

    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }
    public boolean isHasError() { return hasError; }
    public void setHasError(boolean hasError) { this.hasError = hasError; }
    public String getOriginalWrong() { return originalWrong; }
    public void setOriginalWrong(String originalWrong) { this.originalWrong = originalWrong; }
    public String getCorrected() { return corrected; }
    public void setCorrected(String corrected) { this.corrected = corrected; }
    public String getGrammarReason() { return grammarReason; }
    public void setGrammarReason(String grammarReason) { this.grammarReason = grammarReason; }
    public String getMistakeType() { return mistakeType; }
    public void setMistakeType(String mistakeType) { this.mistakeType = mistakeType; }
    public List<String> getObjectivesMet() { return objectivesMet; }
    public void setObjectivesMet(List<String> objectivesMet) { this.objectivesMet = objectivesMet; }
    public boolean isReadyToAdvanceState() { return readyToAdvanceState; }
    public void setReadyToAdvanceState(boolean readyToAdvanceState) { this.readyToAdvanceState = readyToAdvanceState; }
    public boolean isReadyToEnd() { return readyToEnd; }
    public void setReadyToEnd(boolean readyToEnd) { this.readyToEnd = readyToEnd; }
    public List<String> getExpressions() { return expressions; }
    public void setExpressions(List<String> expressions) { this.expressions = expressions; }
}
