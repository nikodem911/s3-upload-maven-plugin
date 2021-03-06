package com.bazaarvoice.maven.plugins.s3.upload;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.Headers;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.transfer.Transfer;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

@Mojo(name = "s3-upload")
public class S3UploadMojo extends AbstractMojo {
    /**
     * Access key for S3.
     */
    @Parameter(property = "s3-upload.accessKey")
    private String accessKey;

    /**
     * Secret key for S3.
     */
    @Parameter(property = "s3-upload.secretKey")
    private String secretKey;

    /**
     * Execute all steps up except the upload to the S3.
     * This can be set to true to perform a "dryRun" execution.
     */
    @Parameter(property = "s3-upload.doNotUpload", defaultValue = "false")
    private boolean doNotUpload;

    /**
     * The file/folder to upload.
     */
    @Parameter(property = "s3-upload.source", required = true)
    private File source;

    /**
     * The bucket to upload into.
     */
    @Parameter(property = "s3-upload.bucketName", required = true)
    private String bucketName;

    /**
     * The file/folder (in the bucket) to create.
     */
    @Parameter(property = "s3-upload.destination", required = true)
    private String destination;

    /**
     * Force override of endpoint for S3 regions such as EU.
     */
    @Parameter(property = "s3-upload.endpoint")
    private String endpoint;

    /**
     * In the case of a directory upload, recursively upload the contents.
     */
    @Parameter(property = "s3-upload.recursive", defaultValue = "false")
    private boolean recursive;

    /**
     * Proxy host
     **/
    @Parameter(property = "s3-upload.proxyHost")
    private String proxyHost;

    /**
     * Proxy port
     **/
    @Parameter(property = "s3-upload.proxyPort")
    private int proxyPort;

    /**
     * Proxy username
     **/
    @Parameter(property = "s3-upload.proxyUsername")
    private String proxyUsername;

    /**
     * Proxy password
     **/
    @Parameter(property = "s3-upload.proxyPassword")
    private String proxyPassword;

    @Override
    public void execute() throws MojoExecutionException {
        if (!source.exists()) {
            throw new MojoExecutionException("File/folder doesn't exist: " + source);
        }

        AmazonS3 s3 = getS3Client(accessKey, secretKey);
        if (endpoint != null) {
            s3.setEndpoint(endpoint);
        }

        if (!s3.doesBucketExistV2(bucketName)) {
            throw new MojoExecutionException("Bucket doesn't exist: " + bucketName);
        }

        if (doNotUpload) {
            getLog().info(String.format("File %s would have be uploaded to s3://%s/%s (dry run)",
                    source, bucketName, destination));

            return;
        }

        boolean success = upload(s3, source);
        if (!success) {
            throw new MojoExecutionException("Unable to upload file to S3.");
        }

        getLog().info(String.format("File %s uploaded to s3://%s/%s",
                source, bucketName, destination));
    }

    private AmazonS3 getS3Client(String accessKey, String secretKey) {
        AWSCredentialsProvider provider;
        if (accessKey != null && secretKey != null) {
            AWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);
            provider = new AWSStaticCredentialsProvider(credentials);
        } else {
            provider = new DefaultAWSCredentialsProviderChain();
        }
        AmazonS3ClientBuilder s3ClientBuilder = AmazonS3ClientBuilder.standard()
                .withCredentials(provider);
        if (proxyHost != null) {
            s3ClientBuilder.withClientConfiguration(new ClientConfiguration()
                    .withProxyHost(proxyHost)
                    .withProxyPort(proxyPort)
                    .withProxyUsername(proxyUsername)
                    .withProxyPassword(proxyPassword));
        }
        return s3ClientBuilder.build();
    }

    private boolean upload(AmazonS3 s3, File sourceFile) throws MojoExecutionException {
        TransferManager mgr = TransferManagerBuilder.standard().withS3Client(s3).build();


        Transfer transfer;
        if (sourceFile.isFile()) {
            transfer = mgr.upload(new PutObjectRequest(bucketName, destination, sourceFile)
                    .withCannedAcl(CannedAccessControlList.BucketOwnerFullControl));
        } else if (sourceFile.isDirectory()) {
            transfer = mgr.uploadDirectory(bucketName, destination, sourceFile, recursive,
                    (file, objectMetadata) -> {
                        /**
                         * This is a terrible hack, but the SDK as of 1.10.69 does not allow setting ACLs
                         * for directory uploads otherwise.
                         */
                        objectMetadata.setHeader(Headers.S3_CANNED_ACL, CannedAccessControlList.BucketOwnerFullControl);
                    });
        } else {
            throw new MojoExecutionException("File is neither a regular file nor a directory " + sourceFile);
        }
        try {
            getLog().debug("Transferring " + transfer.getProgress().getTotalBytesToTransfer() + " bytes...");
            transfer.waitForCompletion();
            getLog().info("Transferred " + transfer.getProgress().getBytesTransferred() + " bytes.");
        } catch (InterruptedException e) {
            return false;
        }

        return transfer.getState() == Transfer.TransferState.Completed;
    }
}
