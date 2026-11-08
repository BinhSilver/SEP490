package com.jlas.game.runtime.neko;

import com.jlas.game.dto.NekoDtos.NekoChoiceView;
import com.jlas.game.runtime.GameWord;

import java.util.List;

/** Một lượt rơi cá. {@code correctChoiceId} chỉ nằm trên server. */
public record NekoRound(String id, int index, GameWord word, List<NekoChoiceView> choices, String correctChoiceId,
                        String bonus) {

    public String textOf(String choiceId) {
        return choices.stream().filter(choice -> choice.id().equals(choiceId)).map(NekoChoiceView::text)
                .findFirst().orElse(null);
    }
}
