package fakes.tamework.api;

public interface ActivityView {
    ActivityHeader header();

    ActivityDomain domain();

    ActivityView withHeader(ActivityHeader header);
}
