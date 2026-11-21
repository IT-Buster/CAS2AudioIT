package com.baktra.cas2audio.features.goldies.domain;

import java.util.List;
import java.util.Objects;

public final class TapeSystem {

    // Goldies archive map.txt, available to the playlist without downloading the archive.
    public static final List<TapeSystem> GOLDIES_SYSTEMS = List.of(
            new TapeSystem("Standard", "US", "us_standard"),
            new TapeSystem("Turbo 2000", "CS", "cs_turbo2000"),
            new TapeSystem("KSO Turbo 2000", "PL", "pl_ksoturbo2000"),
            new TapeSystem("Turbo Blizzard", "PL", "pl_blizzard"),
            new TapeSystem("Atari Super Turbo", "PL", "pl_ast"),
            new TapeSystem("Omicron Turbo", "XX", "xx_omicron_turbo"));


    private final String name;
    private final String country;
    private final String prefixCode;

    public TapeSystem(String name, String country, String prefixCode) {
        this.name = name;
        this.country = country;
        this.prefixCode = prefixCode;
    }

    public String getName() {
        return name;
    }

    public String getCountry() {
        return country;
    }

    public String getPrefixCode() {
        return prefixCode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, country, prefixCode);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TapeSystem that = (TapeSystem) o;
        return Objects.equals(name, that.name) &&
                Objects.equals(country, that.country) &&
                Objects.equals(prefixCode, that.prefixCode);
    }

    public String toString() {
        return name;
    }
}