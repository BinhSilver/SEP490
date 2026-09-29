package com.jlas.shadowing.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class WatchResultDto {
    private boolean countedAsView;
    private int viewCount;
    /** True when this page can stop pinging: the view was counted, is in its 24h cooldown, or the viewer owns the video. */
    private boolean trackingDone;
}
