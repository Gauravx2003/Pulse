package com.pulse.calc.core;

import java.util.List;

/**
 * A formula-module descriptor (task 4.10, design.md decision 5): one per {@code calc/} folder, owned by
 * one track. {@link FormulaRegistry} finds it by classpath scan, as feature modules are found, so a
 * track adds formulas without editing {@code calc/core}.
 *
 * <p>Discovery: a public class with a public no-arg constructor whose simple name ends in
 * {@code Formulas}, in package {@code com.pulse.calc.<id>}: {@code calc/core/CoreFormulas.java},
 * {@code calc/work/WorkFormulas.java}, {@code calc/people/PeopleFormulas.java}. Every formula name
 * starts with the module id ({@code "work.utilization"}).
 */
public interface FormulaModule
{
    /** The folder id: {@code core}, {@code work} or {@code people}. */
    String id();

    /** The formulas this module contributes. */
    List<Formula> formulas();
}
