package com.ads.paragelia.paroxos;

public class LoginResponse {
    private String jwt;
    private String jwtExpiration;
    private String jwtRefreshToken;
    private String jwtRefreshTokenExpiration;
    private String url1 = "https://beta-epsilondigital.epsilonnet.gr/";

    public String getJwt() { return jwt; }
    public String getJwtExpiration() { return jwtExpiration; }
    public String getJwtRefreshToken() { return jwtRefreshToken; }
    public String getJwtRefreshTokenExpiration() { return jwtRefreshTokenExpiration; }
    public String getUrl1() { return url1; }
}