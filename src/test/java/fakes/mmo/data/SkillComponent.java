package fakes.mmo.data;

/** The skill component of the fake mod, the class field only. */
public class SkillComponent {
    public String currentClassId;

    public boolean hasClass() {
        return currentClassId != null;
    }

    public String getCurrentClassId() {
        return currentClassId;
    }
}
