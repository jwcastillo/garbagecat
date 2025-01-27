/**********************************************************************************************************************
 * garbagecat                                                                                                         *
 *                                                                                                                    *
 * Copyright (c) 2008-2023 Mike Millson                                                                               *
 *                                                                                                                    * 
 * All rights reserved. This program and the accompanying materials are made available under the terms of the Eclipse *
 * Public License v1.0 which accompanies this distribution, and is available at                                       *
 * http://www.eclipse.org/legal/epl-v10.html.                                                                         *
 *                                                                                                                    *
 * Contributors:                                                                                                      *
 *    Mike Millson - initial API and implementation                                                                   *
 *********************************************************************************************************************/
package org.eclipselabs.garbagecat.domain.jdk;

import static org.eclipselabs.garbagecat.util.Memory.memory;
import static org.eclipselabs.garbagecat.util.Memory.Unit.KILOBYTES;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipselabs.garbagecat.domain.BlockingEvent;
import org.eclipselabs.garbagecat.domain.CombinedData;
import org.eclipselabs.garbagecat.domain.ParallelEvent;
import org.eclipselabs.garbagecat.domain.PermMetaspaceData;
import org.eclipselabs.garbagecat.util.Memory;
import org.eclipselabs.garbagecat.util.jdk.JdkMath;
import org.eclipselabs.garbagecat.util.jdk.JdkRegEx;
import org.eclipselabs.garbagecat.util.jdk.JdkUtil;
import org.eclipselabs.garbagecat.util.jdk.unified.UnifiedRegEx;
import org.eclipselabs.garbagecat.util.jdk.unified.UnifiedUtil;

/**
 * <p>
 * SHENANDOAH_FULL_GC
 * </p>
 * 
 * <p>
 * Happens when a Degenerated GC does not free enough heap space. For example, an unusually fragmented heap may only be
 * able to be fixed by a Full GC. This last-ditch GC guarantees the application will not fail with OOME if there is at
 * least some memory is available[1].
 * 
 * [1]<a href="https://wiki.openjdk.java.net/display/shenandoah/Main">Shenandoah GC</a>
 * </p>
 * 
 * <h2>Example Logging</h2>
 * 
 * <p>
 * 1) JDK8 preprocessed:
 * </p>
 * 
 * <pre>
 * 2020-03-10T08:03:29.427-0400: 0.489: [Pause Final Mark, 0.313 ms]
 * </pre>
 * 
 * @author <a href="mailto:mmillson@redhat.com">Mike Millson</a>
 * 
 */
public class ShenandoahFullGcEvent extends ShenandoahCollector
        implements BlockingEvent, ParallelEvent, CombinedData, PermMetaspaceData {

    private static final Pattern pattern = Pattern.compile(ShenandoahFullGcEvent.REGEX);

    /**
     * Regular expressions defining the logging.
     */
    private static final String REGEX = "^(" + JdkRegEx.DECORATOR + "|" + UnifiedRegEx.DECORATOR
            + ") [\\[]{0,1}Pause Full " + JdkRegEx.SIZE + "->" + JdkRegEx.SIZE + "\\(" + JdkRegEx.SIZE + "\\)[,]{0,1} "
            + JdkRegEx.DURATION_MS + "[]]{0,1}([,]{0,1} [\\[]{0,1}" + "Metaspace: " + JdkRegEx.SIZE + "->"
            + JdkRegEx.SIZE + "\\(" + JdkRegEx.SIZE + "\\)[]]{0,1})?[ ]*$";

    /**
     * Determine if the logLine matches the logging pattern(s) for this event.
     * 
     * @param logLine
     *            The log line to test.
     * @return true if the log line matches the event pattern, false otherwise.
     */
    public static final boolean match(String logLine) {
        return pattern.matcher(logLine).matches();
    }

    /**
     * Combined size at beginning of GC event.
     */
    private Memory combined;

    /**
     * Combined available space.
     */
    private Memory combinedAvailable;

    /**
     * Combined size at end of GC event.
     */
    private Memory combinedEnd;

    /**
     * The elapsed clock time for the GC event in microseconds (rounded).
     */
    private long duration;

    /**
     * The log entry for the event. Can be used for debugging purposes.
     */
    private String logEntry;

    /**
     * Permanent generation size at beginning of GC event.
     */
    private Memory permGen;

    /**
     * Space allocated to permanent generation.
     */
    private Memory permGenAllocation;

    /**
     * Permanent generation size at end of GC event.
     */
    private Memory permGenEnd;

    /**
     * The time when the GC event started in milliseconds after JVM startup.
     */
    private long timestamp;

    /**
     * Create event from log entry.
     * 
     * @param logEntry
     *            The log entry for the event.
     */
    public ShenandoahFullGcEvent(String logEntry) {
        this.logEntry = logEntry;
        if (logEntry.matches(REGEX)) {
            Matcher matcher = pattern.matcher(logEntry);
            if (matcher.find()) {
                duration = JdkMath
                        .convertMillisToMicros(matcher.group(JdkUtil.DECORATOR_SIZE + UnifiedUtil.DECORATOR_SIZE + 11))
                        .intValue();
                if (matcher.group(1).matches(UnifiedRegEx.DECORATOR)) {
                    long endTimestamp;
                    if (matcher.group(15).matches(UnifiedRegEx.UPTIMEMILLIS)) {
                        endTimestamp = Long.parseLong(matcher.group(JdkUtil.DECORATOR_SIZE + 13));
                    } else if (matcher.group(15).matches(UnifiedRegEx.UPTIME)) {
                        endTimestamp = JdkMath.convertSecsToMillis(matcher.group(JdkUtil.DECORATOR_SIZE + 12))
                                .longValue();
                    } else {
                        if (matcher.group(JdkUtil.DECORATOR_SIZE + 14) != null) {
                            if (matcher.group(JdkUtil.DECORATOR_SIZE + 15).matches(UnifiedRegEx.UPTIMEMILLIS)) {
                                endTimestamp = Long.parseLong(matcher.group(JdkUtil.DECORATOR_SIZE + 17));
                            } else {
                                endTimestamp = JdkMath.convertSecsToMillis(matcher.group(JdkUtil.DECORATOR_SIZE + 16))
                                        .longValue();
                            }
                        } else {
                            // Datestamp only.
                            endTimestamp = JdkUtil.convertDatestampToMillis(matcher.group(15));
                        }
                    }
                    timestamp = endTimestamp - JdkMath.convertMicrosToMillis(duration).longValue();
                } else {
                    // JDK8
                    if (matcher.group(14) != null && matcher.group(14).matches(JdkRegEx.TIMESTAMP)) {
                        timestamp = JdkMath.convertSecsToMillis(matcher.group(14)).longValue();
                    } else if (matcher.group(2).matches(JdkRegEx.TIMESTAMP)) {
                        timestamp = JdkMath.convertSecsToMillis(matcher.group(2)).longValue();
                    } else {
                        // Datestamp only.
                        timestamp = JdkUtil.convertDatestampToMillis(matcher.group(2));
                    }
                }
                combined = memory(matcher.group(JdkUtil.DECORATOR_SIZE + UnifiedUtil.DECORATOR_SIZE + 2),
                        matcher.group(JdkUtil.DECORATOR_SIZE + UnifiedUtil.DECORATOR_SIZE + 4).charAt(0))
                                .convertTo(KILOBYTES);
                combinedEnd = memory(matcher.group(JdkUtil.DECORATOR_SIZE + UnifiedUtil.DECORATOR_SIZE + 5),
                        matcher.group(JdkUtil.DECORATOR_SIZE + UnifiedUtil.DECORATOR_SIZE + 7).charAt(0))
                                .convertTo(KILOBYTES);
                combinedAvailable = memory(matcher.group(JdkUtil.DECORATOR_SIZE + UnifiedUtil.DECORATOR_SIZE + 8),
                        matcher.group(JdkUtil.DECORATOR_SIZE + UnifiedUtil.DECORATOR_SIZE + 10).charAt(0))
                                .convertTo(KILOBYTES);
                if (matcher.group(JdkUtil.DECORATOR_SIZE + UnifiedUtil.DECORATOR_SIZE + 12) != null) {
                    permGen = memory(matcher.group(JdkUtil.DECORATOR_SIZE + UnifiedUtil.DECORATOR_SIZE + 13),
                            matcher.group(JdkUtil.DECORATOR_SIZE + UnifiedUtil.DECORATOR_SIZE + 15).charAt(0))
                                    .convertTo(KILOBYTES);
                    permGenEnd = memory(matcher.group(JdkUtil.DECORATOR_SIZE + UnifiedUtil.DECORATOR_SIZE + 16),
                            matcher.group(JdkUtil.DECORATOR_SIZE + UnifiedUtil.DECORATOR_SIZE + 18).charAt(0))
                                    .convertTo(KILOBYTES);
                    permGenAllocation = memory(matcher.group(JdkUtil.DECORATOR_SIZE + UnifiedUtil.DECORATOR_SIZE + 19),
                            matcher.group(JdkUtil.DECORATOR_SIZE + UnifiedUtil.DECORATOR_SIZE + 21).charAt(0))
                                    .convertTo(KILOBYTES);
                }
            }
        }
    }

    /**
     * Alternate constructor. Create event from values.
     * 
     * @param logEntry
     *            The log entry for the event.
     * @param timestamp
     *            The time when the GC event started in milliseconds after JVM startup.
     * @param duration
     *            The elapsed clock time for the GC event in microseconds.
     */
    public ShenandoahFullGcEvent(String logEntry, long timestamp, int duration) {
        this.logEntry = logEntry;
        this.timestamp = timestamp;
        this.duration = duration;
    }

    public Memory getCombinedOccupancyEnd() {
        return combinedEnd;
    }

    public Memory getCombinedOccupancyInit() {
        return combined;
    }

    public Memory getCombinedSpace() {
        return combinedAvailable;
    }

    public long getDuration() {
        return duration;
    }

    public String getLogEntry() {
        return logEntry;
    }

    public String getName() {
        return JdkUtil.LogEventType.SHENANDOAH_FULL_GC.toString();
    }

    public Memory getPermOccupancyEnd() {
        return permGenEnd;
    }

    public Memory getPermOccupancyInit() {
        return permGen;
    }

    public Memory getPermSpace() {
        return permGenAllocation;
    }

    public long getTimestamp() {
        return timestamp;
    }

    protected void setPermOccupancyEnd(Memory permGenEnd) {
        this.permGenEnd = permGenEnd;
    }

    protected void setPermOccupancyInit(Memory permGen) {
        this.permGen = permGen;
    }

    protected void setPermSpace(Memory permGenAllocation) {
        this.permGenAllocation = permGenAllocation;
    }
}