package org.rouplex.jaxrs.client;

public class HttpResponse {
    String characterEncoding;
    String contentType;
    String locale;

    public String getCharacterEncoding() {
        return characterEncoding;
    }

    public void setCharacterEncoding(String characterEncoding) {
        this.characterEncoding = characterEncoding;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }
}
