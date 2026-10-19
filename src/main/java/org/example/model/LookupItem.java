package org.example.model;

public record LookupItem(int id, String name) {

    @Override
    public String toString() {
        return name;
    }
}