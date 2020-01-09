package uk.gov.pay.publicauth.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import io.dropwizard.auth.Auth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.publicauth.auth.Token;
import uk.gov.pay.publicauth.dao.AuthTokenDao;
import uk.gov.pay.publicauth.exception.TokenNotFoundException;
import uk.gov.pay.publicauth.exception.ValidationException;
import uk.gov.pay.publicauth.model.CreateTokenRequest;
import uk.gov.pay.publicauth.model.TokenHash;
import uk.gov.pay.publicauth.model.TokenLink;
import uk.gov.pay.publicauth.model.TokenSource;
import uk.gov.pay.publicauth.model.TokenState;
import uk.gov.pay.publicauth.model.Tokens;
import uk.gov.pay.publicauth.service.TokenService;

import javax.inject.Singleton;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.Response.Status.UNAUTHORIZED;
import static javax.ws.rs.core.Response.ok;
import static uk.gov.pay.publicauth.model.TokenSource.API;
import static uk.gov.pay.publicauth.model.TokenState.ACTIVE;

@Singleton
@Path("/")
public class PublicAuthResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicAuthResource.class);

    private static final String ACCOUNT_ID_FIELD = "account_id";
    private static final String TOKEN_TYPE_FIELD = "token_type";
    private static final String TYPE_FIELD = "type";
    private static final String TOKEN_LINK_FIELD = "token_link";
    private static final String TOKEN_FIELD = "token";
    private static final String DESCRIPTION_FIELD = "description";
    private static final String CREATED_BY_FIELD = "created_by";

    private final AuthTokenDao authDao;
    private final TokenService tokenService;

    public PublicAuthResource(AuthTokenDao authDao,
                              TokenService tokenService) {
        this.authDao = authDao;
        this.tokenService = tokenService;
    }

    @Path("/v1/api/auth")
    @Timed
    @Produces(APPLICATION_JSON)
    @GET
    public Response authenticate(@Auth Token token) {
        return authDao.findUnRevokedAccount(TokenHash.of(token.getName()))
                .map(tokenInfo -> ok(ImmutableMap.of(
                        ACCOUNT_ID_FIELD, tokenInfo.get(ACCOUNT_ID_FIELD),
                        TOKEN_TYPE_FIELD, tokenInfo.get(TOKEN_TYPE_FIELD).toString())))
                .orElse(Response.status(UNAUTHORIZED))
                .build();
    }

    @Path("/v1/frontend/auth")
    @Timed
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @POST
    public Response createTokenForAccount(@NotNull @Valid CreateTokenRequest createTokenRequest) {

        Tokens token = tokenService.issueTokens();
        authDao.storeToken(token.getHashedToken(), createTokenRequest);
        LOGGER.info("Created token with {}", createTokenRequest.getTokenLink());
        return ok(ImmutableMap.of("token", token.getApiKey())).build();
    }

    @Path("/v1/frontend/auth/{accountId}")
    @Timed
    @Produces(APPLICATION_JSON)
    @GET
    public Response getIssuedTokensForAccount(@PathParam("accountId") String accountId, 
                                              @QueryParam("state") TokenState state,
                                              @QueryParam("type") TokenSource type) {
        state = Optional.ofNullable(state).orElse(ACTIVE);
        type = Optional.ofNullable(type).orElse(API);
        List<Map<String, Object>> tokensWithoutNullRevoked = authDao.findTokensBy(accountId, state, type);
        return ok(ImmutableMap.of("tokens", tokensWithoutNullRevoked)).build();
    }

    @Path("/v1/frontend/auth")
    @Timed
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @PUT
    public Response updateTokenDescription(JsonNode payload) throws ValidationException, TokenNotFoundException {

        validatePayloadHasFields(payload, TOKEN_LINK_FIELD, DESCRIPTION_FIELD);

        TokenLink tokenLink = TokenLink.of(payload.get(TOKEN_LINK_FIELD).asText());
        String description = payload.get(DESCRIPTION_FIELD).asText();

        if (authDao.updateTokenDescription(tokenLink, description)) {
            LOGGER.info("Updated description of token with {}", tokenLink);
            return authDao.findTokenByTokenLink(tokenLink)
                    .map(token -> ok(token).build())
                    .orElseThrow(() -> new TokenNotFoundException("Could not update description of token with " + tokenLink));
        }

        LOGGER.error("Could not update description of token with token_link " + tokenLink);
        throw new TokenNotFoundException("Could not update description of token with token_link " + tokenLink);
    }

    @Path("/v1/frontend/auth/{accountId}")
    @Timed
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @DELETE
    public Response revokeSingleToken(@PathParam("accountId") String accountId, JsonNode payload) throws ValidationException, TokenNotFoundException {

        validatePayloadHasFields(payload, Collections.emptyList(), asList(TOKEN_LINK_FIELD, TOKEN_FIELD));

        if (payload.hasNonNull(TOKEN_FIELD)) {
             return tokenService.extractEncryptedTokenFrom(payload.get(TOKEN_FIELD).asText())
            .map(token -> authDao.revokeSingleToken(accountId, TokenHash.of(token.getName()))
                    .map(this::buildRevokedTokenResponse))
                     .orElseThrow(() -> new TokenNotFoundException("Could not revoke token"))
                     .orElseThrow(() -> new TokenNotFoundException("Could not extract encrypted token while revoking token"));
        } else {
            TokenLink tokenLink = TokenLink.of(payload.get(TOKEN_LINK_FIELD).asText());
            return authDao.revokeSingleToken(accountId, tokenLink)
                    .map(this::buildRevokedTokenResponse)
                    .orElseThrow(() -> new TokenNotFoundException("Could not revoke token with " + tokenLink));
        }

    }

    private Response buildRevokedTokenResponse(String revokedDate) {
        LOGGER.info("revoked token on date {}", revokedDate);
        return ok(ImmutableMap.of("revoked", revokedDate)).build();
    }

    private void validatePayloadHasFields(JsonNode payload, String... expectedFields) throws ValidationException {
        validatePayloadHasFields(payload, asList(expectedFields), Collections.emptyList());
    }

    private void validatePayloadHasFields(JsonNode payload, List<String> expectedFields, List<String> atLeastOneOfTheseFieldsMustExist) throws ValidationException {
        if (payload == null) {
            throw new ValidationException("Body cannot be empty");
        }
        List<String> missingFields = expectedFields
                .stream()
                .filter(expectedKey -> !payload.has(expectedKey))
                .collect(Collectors.toList());
        if (!missingFields.isEmpty()) {
            throw new ValidationException("Missing fields: " + missingFields);
        }

        if (!atLeastOneOfTheseFieldsMustExist.isEmpty() && atLeastOneOfTheseFieldsMustExist
                .stream()
                .noneMatch(payload::has)) {
            throw new ValidationException("At least one of these fields must be present: " + atLeastOneOfTheseFieldsMustExist);
        }
    }
}
