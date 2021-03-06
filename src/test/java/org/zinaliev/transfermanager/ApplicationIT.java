package org.zinaliev.transfermanager;

import com.google.common.io.Resources;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.zinaliev.transfermanager.api.ApiPaths;
import org.zinaliev.transfermanager.api.model.ResponseModel;
import org.zinaliev.transfermanager.api.model.TransferModel;
import org.zinaliev.transfermanager.api.model.UpdateWalletModel;
import org.zinaliev.transfermanager.api.model.WalletModel;
import org.zinaliev.transfermanager.service.storage.InMemoryWalletStorage;
import org.zinaliev.transfermanager.util.ConfigUtils;
import org.zinaliev.transfermanager.util.JsonSerializer;
import org.zinaliev.transfermanager.util.UnirestUtils;
import unirest.HttpResponse;
import unirest.Unirest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.zinaliev.transfermanager.exception.StatusCode.*;

public class ApplicationIT {

    private static final String CONFIG_FILE = "config-test.yaml";
    private static JsonSerializer serializer;
    private static String urlBase;
    private static InMemoryWalletStorage storage;

    @BeforeClass
    public static void beforeAll() {
        String configFile = Resources.getResource(CONFIG_FILE).getPath();
        Application.main(new String[]{configFile});
        serializer = Application.injector.getInstance(JsonSerializer.class);
        storage = Application.injector.getInstance(InMemoryWalletStorage.class);

        ApplicationConfig appConfig = ConfigUtils.readAppConfig(configFile);
        urlBase = "http://localhost:" + appConfig.getServerConfig().getPort();

        UnirestUtils.setupSerializer();
    }

    @AfterClass
    public static void afterAll() {
        Unirest.shutDown();
    }

    @After
    public void afterEach() {
        storage.reset();
    }

    // region { Create, Read, Delete Wallet }

    @Test
    public void testCreateWallet_NewWallet_GetWalletSucceeds() {

        HttpResponse<ResponseModel> createResp = createWallet("123", "USD", 123.45);
        assertEquals(200, createResp.getStatus());
        assertEquals(200, createResp.getBody().getCodeEx());
        assertEquals("OK", createResp.getBody().getMessage());

        HttpResponse<WalletResponseModel> getResp = getWallet("123");
        assertEquals(200, getResp.getStatus());

        WalletResponseModel body = getResp.getBody();
        assertEquals(200, body.getCodeEx());
        assertEquals("OK", body.getMessage());
        assertEquals("USD", body.getData().getCurrencyCode());
        assertEquals(123.45, body.getData().getAmount(), 0);
    }

    @Test
    public void testCreateWallet_UnsupportedCurrency_RespondsWithBadRequest() {
        HttpResponse<ResponseModel> createResp = createWallet("123", "AUD", 123.45);

        assertEquals(400, createResp.getStatus());
        assertEquals(INVALID_CURRENCY.getCode(), createResp.getBody().getCodeEx());
    }

    @Test
    public void testCreateWallet_WithExistingId_RespondsWithBadRequest() {
        createWallet("123", "USD", 123.45);

        HttpResponse<ResponseModel> createResp = createWallet("123", "RUB", 123.45);

        assertEquals(400, createResp.getStatus());
        assertEquals(WALLET_ALREADY_EXISTS.getCode(), createResp.getBody().getCodeEx());
    }

    @Test
    public void testCreateWallet_WithNegativeAmount_RespondsWithBadRequest() {
        HttpResponse<ResponseModel> createResp = createWallet("123", "RUB", -123.45);

        assertEquals(400, createResp.getStatus());
        assertEquals(INVALID_AMOUNT.getCode(), createResp.getBody().getCodeEx());
    }

    @Test
    public void testCreateWallet_InvalidInputBody_RespondsWithBadRequest() {
        HttpResponse<ResponseModel> createResp = Unirest.post(urlBase + ApiPaths.WALLET + "123")
                .body("\"invalid\" : \"payload object\"")
                .asObject(ResponseModel.class);

        assertEquals(400, createResp.getStatus());
        assertEquals(BAD_REQUEST_DEFAULT.getCode(), createResp.getBody().getCodeEx());
    }

    @Test
    public void testGetWallet_NonExistingWallet_RespondsWithNotFound() {
        HttpResponse<WalletResponseModel> response = getWallet("123");

        assertEquals(404, response.getStatus());
        assertEquals(NOT_FOUND_DEFAULT.getCode(), response.getBody().getCodeEx());
    }

    @Test
    public void testUpdateWallet_NonExistingWallet_RespondsWithNotFound() {
        HttpResponse<ResponseModel> response = updateWallet("123", 0);

        assertEquals(404, response.getStatus());
        assertEquals(NOT_FOUND_DEFAULT.getCode(), response.getBody().getCodeEx());
    }

    @Test
    public void testUpdateWallet_NegativeAmount_RespondsWithBadRequest() {
        createWallet("123", "USD", 10);
        HttpResponse<ResponseModel> response = updateWallet("123", -10);

        assertEquals(400, response.getStatus());
        assertEquals(INVALID_AMOUNT.getCode(), response.getBody().getCodeEx());
    }

    @Test
    public void testUpdateWallet_NonNegativeAmount_GetReturnsModifiedWallet() {
        createWallet("123", "USD", 10);

        HttpResponse<ResponseModel> updateResp = updateWallet("123", 11);
        assertEquals(200, updateResp.getStatus());
        assertEquals(200, updateResp.getBody().getCodeEx());

        HttpResponse<WalletResponseModel> getResp = getWallet("123");
        assertEquals(11, getResp.getBody().getData().getAmount(), 0);

        updateResp = updateWallet("123", 0);
        assertEquals(200, updateResp.getStatus());
        assertEquals(200, updateResp.getBody().getCodeEx());

        getResp = getWallet("123");
        assertEquals(0, getResp.getBody().getData().getAmount(), 0);
    }

    @Test
    public void testDeleteWallet_EmptyWallet_GetWalletRespondsWithNotFound() {
        createWallet("123", "USD", 0);
        HttpResponse<ResponseModel> deleteResp = deleteWallet("123");

        assertEquals(200, deleteResp.getStatus());
        assertEquals(200, deleteResp.getBody().getCodeEx());

        HttpResponse<WalletResponseModel> getResponse = getWallet("123");
        assertEquals(404, getResponse.getStatus());
        assertEquals(NOT_FOUND_DEFAULT.getCode(), getResponse.getBody().getCodeEx());
    }

    @Test
    public void testDeleteWallet_NonEmptyWallet_RespondsWithBadRequest() {
        createWallet("123", "USD", 100);
        HttpResponse<ResponseModel> deleteResp = deleteWallet("123");

        assertEquals(400, deleteResp.getStatus());
        assertEquals(NON_EMPTY_WALLET_DELETION.getCode(), deleteResp.getBody().getCodeEx());
    }

    @Test
    public void testDeleteWallet_NonExistingWallet_RespondsWithNotFound() {
        HttpResponse<ResponseModel> response = deleteWallet("123");

        assertEquals(404, response.getStatus());
        assertEquals(404, response.getBody().getCodeEx());
    }

    // endregion

    @Test
    public void testTransfer_ValidInput_WalletsInfoModified() {
        createWallet("123", "USD", 100.50);
        createWallet("456", "USD", 0.50);

        HttpResponse<ResponseModel> transferResp = transfer("123", "456", 10.50);
        assertEquals(200, transferResp.getStatus());
        assertEquals(200, transferResp.getBody().getCodeEx());
        assertEquals("OK", transferResp.getBody().getMessage());

        HttpResponse<WalletResponseModel> walletA = getWallet("123");
        HttpResponse<WalletResponseModel> walletB = getWallet("456");

        assertEquals(200, walletA.getStatus());
        assertEquals(200, walletB.getStatus());

        assertEquals(90, walletA.getBody().getData().getAmount(), 0);
        assertEquals(11, walletB.getBody().getData().getAmount(), 0);
    }

    @Test
    public void testTransfer_InvalidInputBody_RespondsWithBadRequest() {

        HttpResponse<ResponseModel> transferResp = Unirest.post(urlBase + ApiPaths.WALLET + "123" + ApiPaths.TRANSFER)
                .body("\"invalid\" : \"payload object\"")
                .asObject(ResponseModel.class);

        assertEquals(400, transferResp.getStatus());
        assertEquals(BAD_REQUEST_DEFAULT.getCode(), transferResp.getBody().getCodeEx());
    }

    @Test
    public void testTransfer_InsufficientSourceWalletMoney_RespondsWithBadRequest() {
        createWallet("123", "USD", 100.50);
        createWallet("456", "USD", 0.50);

        HttpResponse<ResponseModel> transferResp = transfer("123", "456", 100.51);

        assertEquals(400, transferResp.getStatus());
        assertEquals(INSUFFICIENT_MONEY.getCode(), transferResp.getBody().getCodeEx());
    }

    @Test
    public void testTransfer_WalletsWithDifferentCurrencies_RespondsWithBadRequest() {
        createWallet("123", "USD", 100.50);
        createWallet("456", "EUR", 0.50);

        HttpResponse<ResponseModel> transferResp = transfer("123", "456", 1);

        assertEquals(400, transferResp.getStatus());
        assertEquals(INVALID_CURRENCY.getCode(), transferResp.getBody().getCodeEx());
    }

    @Test
    public void testTransfer_SameWallets_RespondsWithBadRequest() {
        createWallet("123", "USD", 100.50);

        HttpResponse<ResponseModel> transferResp = transfer("123", "123", 1);

        assertEquals(400, transferResp.getStatus());
        assertEquals(INVALID_WALLET_ID.getCode(), transferResp.getBody().getCodeEx());
    }

    @Test
    public void testTransfer_TargetWalletNotExists_RespondsWithNotFound() {
        createWallet("123", "USD", 100.50);

        HttpResponse<ResponseModel> transferResp = transfer("123", "456", 1);

        assertEquals(404, transferResp.getStatus());
        assertEquals(NOT_FOUND_DEFAULT.getCode(), transferResp.getBody().getCodeEx());
    }

    @Test
    public void testTransfer_SourceWalletNotExists_RespondsWithNotFound() {
        createWallet("123", "USD", 100.50);

        HttpResponse<ResponseModel> transferResp = transfer("456", "123", 1);

        assertEquals(404, transferResp.getStatus());
        assertEquals(NOT_FOUND_DEFAULT.getCode(), transferResp.getBody().getCodeEx());
    }

    @Test
    public void testTransfer_NegativeTransferredAmount_RespondsWithBadRequest() {
        createWallet("123", "USD", 100.50);
        createWallet("456", "USD", 200.50);

        HttpResponse<ResponseModel> transferResp = transfer("123", "456", -1);

        assertEquals(400, transferResp.getStatus());
        assertEquals(INVALID_AMOUNT.getCode(), transferResp.getBody().getCodeEx());
    }

    // region { Helper Methods }

    private HttpResponse<ResponseModel> createWallet(String id, String currencyCode, double amount) {
        WalletModel body = new WalletModel(currencyCode, amount);

        return preValidate(
                Unirest.post(urlBase + ApiPaths.WALLET + id)
                        .body(serializer.toJson(body))
                        .asObject(ResponseModel.class)
        );
    }

    private HttpResponse<WalletResponseModel> getWallet(String id) {
        return preValidateWallet(
                Unirest.get(urlBase + ApiPaths.WALLET + id)
                        .asObject(WalletResponseModel.class)
        );
    }

    private HttpResponse<ResponseModel> updateWallet(String id, double amount) {
        UpdateWalletModel body = new UpdateWalletModel(amount);

        return preValidate(
                Unirest.patch(urlBase + ApiPaths.WALLET + id)
                        .body(serializer.toJson(body))
                        .asObject(ResponseModel.class)
        );
    }

    private HttpResponse<ResponseModel> deleteWallet(String id) {
        return preValidate(
                Unirest.delete(urlBase + ApiPaths.WALLET + id)
                        .asObject(ResponseModel.class)
        );
    }


    private HttpResponse<ResponseModel> transfer(String sourceWalletId, String targetWalletId, double amount) {
        TransferModel body = new TransferModel(targetWalletId, amount);

        return preValidate(
                Unirest.post(urlBase + ApiPaths.WALLET + sourceWalletId + ApiPaths.TRANSFER)
                        .body(serializer.toJson(body))
                        .asObject(ResponseModel.class)
        );
    }

    private HttpResponse<ResponseModel> preValidate(HttpResponse<ResponseModel> response) {
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getMessage());

        return response;
    }

    private HttpResponse<WalletResponseModel> preValidateWallet(HttpResponse<WalletResponseModel> response) {
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getMessage());

        if (response.getBody().getCodeEx() == 200) {
            assertNotNull(response.getBody().getData());
            assertNotNull(response.getBody().getData().getCurrencyCode());
        }

        return response;
    }

    private static class WalletResponseModel extends ResponseModel<WalletModel> {
    }

    // endregion
}
