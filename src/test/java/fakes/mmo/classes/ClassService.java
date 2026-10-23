package fakes.mmo.classes;

import fakes.mmo.data.SkillComponent;

/** The class service of the fake mod. */
public final class ClassService {
    private static final ClassService INSTANCE = new ClassService();

    public static ClassService getInstance() {
        return INSTANCE;
    }

    public boolean isAvailable() {
        return true;
    }

    public ClassDefinition getCurrentClass(SkillComponent skills) {
        return skills == null || skills.currentClassId == null ? null : new ClassDefinition(skills.currentClassId);
    }
}
