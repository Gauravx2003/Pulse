package com.pulse.calc.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Number rounding and printing exactly as the prototype does it, so ports match the oracle to the
 * last displayed digit (design.md decision 5).
 *
 * <ul>
 *   <li>{@link #fixed(double, int)} is {@code +x.toFixed(n)}: the exact binary value rounded half up.</li>
 *   <li>{@link #round(double, int)} is {@code Math.round(x * 10^n) / 10^n}: rounds the scaled double.</li>
 *   <li>{@link #text(double)} is {@code String(x)} for the ranges shown on screen.</li>
 * </ul>
 */
public final class Num
{
    private Num()
    {
    }

    /** {@code +x.toFixed(decimals)}. */
    public static double fixed(double x, int decimals)
    {
        return new BigDecimal(x).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    /** {@code x.toFixed(decimals)} as text. */
    public static String fixedText(double x, int decimals)
    {
        String s = new BigDecimal(x).setScale(decimals, RoundingMode.HALF_UP).toPlainString();

        return s.matches("-0(\\.0*)?") ? s.substring(1) : s;
    }

    /** {@code Math.round(x * 10^decimals) / 10^decimals}. */
    public static double round(double x, int decimals)
    {
        double scale = Math.pow(10, decimals);

        return Math.floor(x * scale + 0.5) / scale;
    }

    /** {@code Math.round(x)}: halves round up, as in JavaScript. */
    public static long round(double x)
    {
        return (long) Math.floor(x + 0.5);
    }

    /** {@code String(x)}: "3" for whole numbers, "2.5" otherwise. */
    public static String text(double x)
    {
        if (x == Math.rint(x) && Math.abs(x) < 1e15)
        {
            return Long.toString((long) x);
        }

        return BigDecimal.valueOf(x).stripTrailingZeros().toPlainString();
    }
}
