package com.kevin.phonekline;

import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class PhoneMcpClientTest {
    @Test public void expiredNotificationResourceRenegotiatesAndRediscoversUri() throws Exception {
        try (ServerSocket server=new ServerSocket(0,4,InetAddress.getLoopbackAddress())) {
            ExecutorService fixture=Executors.newSingleThreadExecutor();
            Future<?> responses=fixture.submit(()->{
                try {
                    for(int step=0;step<6;step++) try(Socket socket=server.accept()) {
                        BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        String header;
                        int bytes=0;
                        while((header=in.readLine())!=null && !header.isEmpty()) {
                            if(header.regionMatches(true,0,"Content-Length:",0,15)) bytes=Integer.parseInt(header.substring(15).trim());
                        }
                        char[] payload=new char[bytes];
                        for(int at=0;at<bytes;) {
                            int n=in.read(payload,at,bytes-at);
                            if(n<0)throw new EOFException();
                            at+=n;
                        }
                        JSONObject req=new JSONObject(new String(payload));
                        JSONObject response=new JSONObject().put("jsonrpc","2.0").put("id",req.get("id"));
                        String uri="gupiao://notifications/"+(step<3?"old":"new");
                        switch(step%3) {
                            case 0:
                                assertEquals("server/discover",req.getString("method"));
                                response.put("result",new JSONObject());
                                break;
                            case 1:
                                assertEquals("get_notification_stream",req.getJSONObject("params").getString("name"));
                                response.put("result",new JSONObject().put("structuredContent",new JSONObject().put("resource_uri",uri)));
                                break;
                            default:
                                assertEquals("resources/read",req.getString("method"));
                                assertEquals(uri,req.getJSONObject("params").getString("uri"));
                                if(step==2) response.put("error",new JSONObject().put("code",-32002).put("message","resource expired"));
                                else response.put("result",new JSONObject().put("contents",new JSONArray().put(
                                        new JSONObject().put("uri",uri).put("text","{\"notifications\":[]}"))));
                        }
                        byte[] data=response.toString().getBytes(StandardCharsets.UTF_8);
                        OutputStream out=socket.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+data.length+
                                "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(data); out.flush();
                    }
                } catch(Exception error) { throw new RuntimeException(error); }
            });
            try {
                PhoneMcpClient client=new PhoneMcpClient("http://127.0.0.1:"+server.getLocalPort(),"test-only");
                assertThrows(IOException.class,client::notifications);
                assertEquals(0,client.notifications().getJSONArray("notifications").length());
                responses.get(5,TimeUnit.SECONDS);
            } finally { fixture.shutdownNow(); }
        }
    }
}
