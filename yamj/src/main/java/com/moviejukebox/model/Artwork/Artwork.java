/*
 *      Copyright (c) 2004-2013 YAMJ Members
 *      http://code.google.com/p/moviejukebox/people/list
 *
 *      This file is part of the Yet Another Movie Jukebox (YAMJ).
 *
 *      The YAMJ is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU General Public License as published by
 *      the Free Software Foundation, either version 3 of the License, or
 *      any later version.
 *
 *      YAMJ is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU General Public License for more details.
 *
 *      You should have received a copy of the GNU General Public License
 *      along with the YAMJ.  If not, see <http://www.gnu.org/licenses/>.
 *
 *      Web: http://code.google.com/p/moviejukebox/
 *
 */
package com.moviejukebox.model.Artwork;

import com.moviejukebox.model.Movie;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * A class to store all the artwork associated with a movie object
 *
 * @author stuart.boston
 *
 */
public class Artwork {

    private ArtworkType type;       // The type of the artwork.
    private String sourceSite; // Where the artwork originated from
    private String url;        // The original URL of the artwork (may be used as key)
    private Map<ArtworkSize, ArtworkFile> sizes; // The hash should be the size that is passed as part of the ArtworkSize

    /**
     * Create an Artwork object with a set of sizes
     *
     * @param type The type of the artwork
     * @param sourceSite The source site the artwork came from
     * @param url The URL of the artwork
     * @param sizes A list of the artwork files to add
     */
    public Artwork(ArtworkType type, String sourceSite, String url, Collection<ArtworkFile> sizes) {
        this.type = type;
        this.sourceSite = sourceSite;
        this.url = url;

        this.sizes = new EnumMap<ArtworkSize, ArtworkFile>(ArtworkSize.class);
        for (ArtworkFile artworkFile : sizes) {
            this.addSize(artworkFile);
        }
    }

    /**
     * Create an Artwork object with a single size
     *
     * @param type The type of the artwork
     * @param sourceSite The source site the artwork came from
     * @param url The URL of the artwork
     * @param size An artwork files to add
     */
    public Artwork(ArtworkType type, String sourceSite, String url, ArtworkFile size) {
        this.type = type;
        this.sourceSite = sourceSite;
        this.url = url;
        this.sizes = new EnumMap<ArtworkSize, ArtworkFile>(ArtworkSize.class);
        this.addSize(size);
    }

    /**
     * Create a blank Artwork object
     */
    public Artwork() {
        this.sourceSite = Movie.UNKNOWN;
        this.type = null;
        this.url = Movie.UNKNOWN;
        this.sizes = new EnumMap<ArtworkSize, ArtworkFile>(ArtworkSize.class);
    }

    /**
     * Add the ArtworkFile to the list, overwriting anything already there
     *
     * @param size
     */
    public final void addSize(ArtworkFile size) {
        sizes.put(size.getSize(), size);
    }

    public Collection<ArtworkFile> getSizes() {
        return sizes.values();
    }

    public ArtworkFile getSize(String size) {
        return sizes.get(ArtworkSize.valueOf(size.toUpperCase()));
    }

    public ArtworkFile getSize(ArtworkSize size) {
        return sizes.get(size);
    }

    /**
     * @return the sourceSite
     */
    public String getSourceSite() {
        return sourceSite;
    }

    /**
     * @return the type
     */
    public ArtworkType getType() {
        return type;
    }

    /**
     * @return the url
     */
    public String getUrl() {
        return url;
    }

    /**
     * @param sourceSite the sourceSite to set
     */
    public void setSourceSite(String sourceSite) {
        this.sourceSite = sourceSite;
    }

    /**
     * @param type the type to set
     */
    public void setType(ArtworkType type) {
        this.type = type;
    }

    /**
     * @param url the URL to set
     */
    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 79 * hash + (this.type != null ? this.type.hashCode() : 0);
        hash = 79 * hash + (this.sourceSite != null ? this.sourceSite.hashCode() : 0);
        hash = 79 * hash + (this.url != null ? this.url.hashCode() : 0);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return Boolean.FALSE;
        }

        if (getClass() != obj.getClass()) {
            return Boolean.FALSE;
        }

        final Artwork other = (Artwork) obj;
        if (this.type != other.type) {
            return Boolean.FALSE;
        }

        if ((this.sourceSite == null) ? (other.sourceSite != null) : !this.sourceSite.equals(other.sourceSite)) {
            return Boolean.FALSE;
        }

        if ((this.url == null) ? (other.url != null) : !this.url.equals(other.url)) {
            return Boolean.FALSE;
        }

        return Boolean.TRUE;
    }

    public int compareTo(Artwork anotherArtwork) {
        if (this.sourceSite.equals(anotherArtwork.getSourceSite())
                && this.type.equals(anotherArtwork.getType())
                && this.url.equals(anotherArtwork.getUrl())) {
            return 0;
        } else {
            return 1;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[Artwork=");
        sb.append("[type=").append(type);
        sb.append("][sourceSite=").append(sourceSite);
        sb.append("][url=").append(url);
        sb.append("][sizes=").append(sizes);
        sb.append("]]");
        return sb.toString();
}
}
