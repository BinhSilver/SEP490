package com.jlas.mypaths;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * [JLAS-W0108] Owner 24/11 "Lộ trình của tôi": a learner may study two communication goals at once, so every
 * communication API and page takes {@code ?goal=TRAVEL} to say which path it means. The filter keeps that goal for the
 * request; without it the communication pages use the learner's newest active communication path, as before.
 */
@Component
public class MyPathsGoalFilter extends OncePerRequestFilter {

    private static final Pattern GOAL = Pattern.compile("^[A-Z_]{1,40}$");
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** The communication goal this request asked for, or null. */
    public static String currentGoal() {
        return CURRENT.get();
    }

    /** For tests and internal calls that act on one path; always pair with {@link #clear()}. */
    public static void use(String goal) {
        CURRENT.set(normalize(goal));
    }

    public static void clear() {
        CURRENT.remove();
    }

    static String normalize(String goal) {
        if (goal == null) return null;
        String g = goal.strip().toUpperCase(Locale.ROOT);
        return GOAL.matcher(g).matches() ? g : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !(uri.startsWith("/api/learning/comm") || uri.startsWith("/learning/comm"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String before = CURRENT.get();
        CURRENT.set(normalize(request.getParameter("goal")));
        try {
            chain.doFilter(request, response);
        } finally {
            if (before == null) CURRENT.remove();
            else CURRENT.set(before);
        }
    }
}
