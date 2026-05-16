package dev.medveed.safeshare.net;

import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.HEAD;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Streaming;
import retrofit2.http.GET;

public interface ApiService {

    @POST("api/files")
    Call<UploadResponse> upload(
            @Header("X-SafeShare-Ttl-Seconds") long ttlSeconds,
            @Header("X-SafeShare-Max-Downloads") long maxDownloads,
            @Header("X-SafeShare-Owner-Token") String ownerTokenHashHex,
            @Body RequestBody body
    );

    @Streaming
    @GET("api/files/{file_id}")
    Call<okhttp3.ResponseBody> download(@Path("file_id") String fileId);

    @HEAD("api/files/{file_id}")
    Call<Void> head(@Path("file_id") String fileId);

    @DELETE("api/files/{file_id}")
    Call<Void> revoke(
            @Path("file_id") String fileId,
            @Header("X-SafeShare-Owner-Token") String ownerTokenHex
    );
}
