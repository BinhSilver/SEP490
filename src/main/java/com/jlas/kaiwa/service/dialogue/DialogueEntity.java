package com.jlas.kaiwa.service.dialogue;

/**
 * One node in the free-chat entity stack (topic hierarchy).
 */
public class DialogueEntity {

    private String type = "topic";
    private String name;
    private String parent;
    private int depth;
    private int lastMentionTurn;

    public DialogueEntity() {
    }

    public DialogueEntity(String type, String name, String parent, int depth, int lastMentionTurn) {
        this.type = type != null ? type : "topic";
        this.name = name;
        this.parent = parent;
        this.depth = depth;
        this.lastMentionTurn = lastMentionTurn;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getParent() {
        return parent;
    }

    public void setParent(String parent) {
        this.parent = parent;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public int getLastMentionTurn() {
        return lastMentionTurn;
    }

    public void setLastMentionTurn(int lastMentionTurn) {
        this.lastMentionTurn = lastMentionTurn;
    }
}
