package com.jlas.jlptpath.service;

/**
 * [JLAS-W0108] The reward side of the JLPT path (CP, streak, badges): the bean behind {@link JpathRewardHook}. Every
 * method joins the caller's transaction (MANDATORY); the callers serialize under the path lock, so each reward is
 * queued once per tab/day, and the CP ledger's reference key drops any repeat.
 */
public interface JpathRewardService extends JpathRewardHook {
}
