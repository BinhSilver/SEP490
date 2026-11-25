package com.jlas.cpath;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] F240 read-only bridge: facts of one communication path that live in package-private parts of
 * {@link CpathService} (its situation list in study order), for the entry/exit summary in another package.
 */
@Component
public class CpathPathFacts {

    private final CpathService comm;

    public CpathPathFacts(CpathService comm) {
        this.comm = comm;
    }

    /** Situations the path studies (its goal, fields, priority topics and depth applied). */
    public int situationCount(Map<String, Object> pathRow) {
        return comm.orderedSituations(pathRow).size();
    }
}
