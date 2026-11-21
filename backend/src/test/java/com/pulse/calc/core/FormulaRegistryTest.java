package com.pulse.calc.core;

import com.pulse.calc.people.PeopleFormulas;
import com.pulse.calc.work.ProbeFormulas;
import com.pulse.calc.work.WorkFormulas;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 4.11: the {@code calc/core}, {@code calc/work} and {@code calc/people} descriptors are found by
 * classpath scan, and a descriptor added in {@code calc/work} (the test-only {@link ProbeFormulas})
 * appears without editing {@code calc/core}.
 */
class FormulaRegistryTest
{
    @Test
    void discoversTheThreeFoldersAndTheirExamples()
    {
        FormulaRegistry registry = FormulaRegistry.discover();

        Set<String> ids = Set.copyOf(registry.modules().stream().map(FormulaModule::id).toList());

        assertEquals(Set.of("core", "work", "people"), ids);
        assertNotNull(registry.formula("core.index"));
        assertNotNull(registry.formula("work.workingDays"));
        assertNotNull(registry.formula("people.serviceMonths"));
        assertTrue(registry.modules().stream().anyMatch(m -> m instanceof WorkFormulas));
        assertTrue(registry.modules().stream().anyMatch(m -> m instanceof PeopleFormulas));
    }

    @Test
    void aDescriptorAddedInCalcWorkAppearsWithoutEditingCore()
    {
        FormulaRegistry registry = FormulaRegistry.discover();

        assertTrue(registry.modules().stream().anyMatch(m -> m instanceof ProbeFormulas));
        assertNotNull(registry.formula("work.probe"));
        assertEquals(Set.of(PeriodCalendar.SPRINT), registry.formula("work.probeSprint").periodKinds());

        // CalcService serves it as it serves the core formulas.
        CalcService calc = new CalcService(null, PulseClock.of(java.time.Clock.systemUTC(), "2026-09-28", false), registry);

        assertTrue(calc.has("work.probe"));
        assertTrue(calc.formulas().containsAll(List.of("core.index", "work.workingDays", "people.serviceMonths", "work.probe")));
    }

    @Test
    void namesMustStartWithTheModuleIdAndBeUnique()
    {
        Formula.Compute none = (ctx, p) -> Future.succeededFuture(null);

        FormulaModule wrongPrefix = module("work", Formula.of("core.sneaky", Set.of(), Set.of(), none));

        assertThrows(IllegalStateException.class, () -> FormulaRegistry.of(List.of(wrongPrefix)));

        FormulaModule a = module("work", Formula.of("work.same", Set.of(), Set.of(), none));
        FormulaModule b = module("work", Formula.of("work.same", Set.of(), Set.of(), none));

        assertThrows(IllegalStateException.class, () -> FormulaRegistry.of(List.of(a, b)));

        // Two descriptors of one folder with different formulas are fine.
        FormulaModule c = module("work", Formula.of("work.other", Set.of(), Set.of(), none));

        assertEquals(2, FormulaRegistry.of(List.of(a, c)).formulas().size());

        assertThrows(IllegalArgumentException.class, () -> Formula.of("Bad Name", Set.of(), Set.of(), none));
        assertThrows(IllegalArgumentException.class, () -> new Formula("work.x", Set.of("person"), Set.of(),
                Set.of(PeriodCalendar.SPRINT), none));
    }

    private static FormulaModule module(String id, Formula... formulas)
    {
        return new FormulaModule()
        {
            @Override
            public String id()
            {
                return id;
            }

            @Override
            public List<Formula> formulas()
            {
                return List.of(formulas);
            }
        };
    }
}
