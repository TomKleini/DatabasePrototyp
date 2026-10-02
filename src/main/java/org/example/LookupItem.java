package org.example;

public record LookupItem(int id, String name) {

    @Override
    public String toString() {
        return name;
    }
}