package com.adesa.attendance.campaign;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
public final class CampaignRules {
    private CampaignRules(){
    }
    static final SecureRandom RANDOM=new SecureRandom();
    static final List<String> CATEGORIES=List.of("REGULAR","CONSULTATION","PREMIUM","GIFT","GRAND");
    static final Set<String> BUSINESSES=Set.of("ENERGY","MEDIA","SERVICES","GENERAL");
    static final Set<String> GAMES=Set.of("SPIN","MATCH","SCRATCH","TRIVIA");
    static String token(){
        byte[] bytes=new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String hash(String value){
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch(Exception e){
            throw new IllegalStateException(e);
        }
    }
    public static String email(String value){
        String v=value==null?"":value.strip().toLowerCase(Locale.ROOT);
        require(v.length()<=254&&v.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"),"Enter a valid email address.");
        return v;
    }
    public static String phone(String value){
        String v=value==null?"":value.replaceAll("[^0-9+]","");
        if(v.startsWith("00"))v="+"+v.substring(2);
        if(v.matches("0[0-9]{10}"))v="+234"+v.substring(1);
        if(v.matches("234[0-9]{10}"))v="+"+v;
        require(v.matches("\\+[1-9][0-9]{7,14}"),"Use a valid international phone number, such as +2348012345678.");
        return v;
    }
    static String text(Object value,int max){
        String v=value==null?"":String.valueOf(value).strip();
        require(v.length()<=max,"Text exceeds the allowed length.");
        return v;
    }
    static void require(boolean ok,String message){
        if(!ok)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    static ResponseStatusException conflict(String message){
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
    public static String performance(String game,int score,int moves){
        if(game.equals("MATCH")){
            if(score==6)return moves<=8?"GRAND":moves<=12?"PREMIUM":"REGULAR";
            return score>=4?"CONSULTATION":"GIFT";
        }
        return score==5?"GRAND":score==4?"PREMIUM":score==3?"REGULAR":score==2?"CONSULTATION":"GIFT";
    }
    static void noDiscount(String value){
        require(!value.contains("%")&&!value.toLowerCase(Locale.ROOT).matches("(?s).*\\b(percent|percentage)\\b.*"),"Percentage discounts are not permitted in campaign prizes or terms.");
    }
    public static String category(Map<String,Integer> weights,Set<String> available,int draw){
        int total=available.stream().mapToInt(c->weights.getOrDefault(c,0)).sum();
        require(total>0,"No approved rewards are available.");
        require(draw>=0&&draw<total,"Invalid draw.");
        int cursor=0;
        for(String c:CATEGORIES)if(available.contains(c)){
            cursor+=weights.getOrDefault(c,0);
            if(draw<cursor)return c;
        }
        throw new IllegalStateException();
    }
}
