/*
 * Copyright 2020 DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.fallout.service.resources.server;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.dropwizard.auth.Auth;
import io.swagger.annotations.Api;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.fallout.exceptions.InvalidConfigurationException;
import com.datastax.fallout.harness.ArtifactChecker;
import com.datastax.fallout.harness.Checker;
import com.datastax.fallout.harness.Module;
import com.datastax.fallout.harness.TestDefinition;
import com.datastax.fallout.ops.ConfigurationManager;
import com.datastax.fallout.ops.FalloutPropertySpecs;
import com.datastax.fallout.ops.NodeGroup;
import com.datastax.fallout.ops.PropertyBasedComponent;
import com.datastax.fallout.ops.PropertyGroup;
import com.datastax.fallout.ops.PropertySpec;
import com.datastax.fallout.ops.Provisioner;
import com.datastax.fallout.ops.Utils;
import com.datastax.fallout.ops.WritablePropertyGroup;
import com.datastax.fallout.runner.ActiveTestRunFactory;
import com.datastax.fallout.runner.Artifacts;
import com.datastax.fallout.runner.QueuingTestRunner;
import com.datastax.fallout.runner.UserCredentialsFactory;
import com.datastax.fallout.service.FalloutConfiguration;
import com.datastax.fallout.service.QueueAdminTask;
import com.datastax.fallout.service.core.DeletedTest;
import com.datastax.fallout.service.core.DeletedTestRun;
import com.datastax.fallout.service.core.PerformanceReport;
import com.datastax.fallout.service.core.ReadOnlyTestRun;
import com.datastax.fallout.service.core.Test;
import com.datastax.fallout.service.core.TestCompletionNotification;
import com.datastax.fallout.service.core.TestRun;
import com.datastax.fallout.service.core.User;
import com.datastax.fallout.service.db.PerformanceReportDAO;
import com.datastax.fallout.service.db.TestDAO;
import com.datastax.fallout.service.db.TestRunDAO;
import com.datastax.fallout.service.views.FalloutView;
import com.datastax.fallout.service.views.LinkedTestRuns;
import com.datastax.fallout.service.views.LinkedTests;
import com.datastax.fallout.service.views.MainView;
import com.datastax.fallout.util.Exceptions;
import com.datastax.fallout.util.TestRunUtils;

import static com.datastax.fallout.service.cli.GenerateNginxConf.NginxConfParams.NGINX_DIRECT_ARTIFACTS_LOCATION;
import static com.datastax.fallout.service.views.FalloutView.uriFor;
import static com.datastax.fallout.service.views.LinkedTestRuns.TableDisplayOption.*;
import static com.datastax.fallout.util.YamlUtils.loadYaml;

@Api
@Path("/tests")
public class TestResource
{
    private static final Logger logger = LoggerFactory.getLogger(TestResource.class);

    public static final String EMAIL_PATTERN =
        "[_A-Za-z0-9-]+(?:\\.[_A-Za-z0-9-]+)*@[A-Za-z0-9]+(?:\\.[A-Za-z0-9]+)*(?:\\.[A-Za-z]{2,})";

    public static final String ID_PATTERN =
        "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    public static final String NAME_PATTERN = "[0-9a-zA-Z\\.\\-_]+";

    private static final YAMLMapper yamlMapper = new YAMLMapper();
    public static final Pattern namePattern = Pattern.compile(NAME_PATTERN);
    private final TestDAO testDAO;
    private final FalloutConfiguration configuration;
    private final TestRunDAO testRunDAO;
    private final ActiveTestRunFactory activeTestRunFactory;
    private final UserCredentialsFactory userCredentialsFactory;
    private final PerformanceReportDAO reportDAO;
    private final QueuingTestRunner testRunner;
    private final QueueAdminTask queueAdminTask;
    private final MainView mainView;

    public TestResource(FalloutConfiguration configuration, TestDAO testDAO, TestRunDAO testRunDAO,
        ActiveTestRunFactory activeTestRunFactory,
        UserCredentialsFactory userCredentialsFactory, PerformanceReportDAO reportDAO,
        QueuingTestRunner testRunner, QueueAdminTask queueAdminTask, MainView mainView)
    {
        this.configuration = configuration;
        this.testDAO = testDAO;
        this.testRunDAO = testRunDAO;
        this.activeTestRunFactory = activeTestRunFactory;
        this.userCredentialsFactory = userCredentialsFactory;
        this.reportDAO = reportDAO;
        this.testRunner = testRunner;
        this.queueAdminTask = queueAdminTask;
        this.mainView = mainView;
    }

    private Response saveTestAndBuildResponse(User user, Test test,
        Function<URI, Response.ResponseBuilder> responseBuilderF)
    {
        try
        {
            activeTestRunFactory.validateTestDefinition(test, userCredentialsFactory);
        }
        catch (Exception e)
        {
            logger.info("createTestApi failed for " + user.getEmail(), e);
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                .entity(ExceptionUtils.getStackTrace(e)).build());
        }

        testDAO.add(test);

        URI redirectURI = UriBuilder.fromResource(TestResource.class)
            .path("{name}/api")
            .build(test.getName());

        return responseBuilderF.apply(redirectURI).entity(test).build();
    }

    @POST
    @Path("{name:" + NAME_PATTERN + "}/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes("application/yaml")
    public Response createTestApi(@Auth User user, @PathParam("name") String name, String yaml)
    {
        UUID testId = UUID.randomUUID();
        String userEmail = user.getEmail();

        //Not sure we need LWT here so keeping it simple
        Test existing = testDAO.get(userEmail, name);
        DeletedTest existsDeleted = testDAO.getDeleted(userEmail, name);
        if (existing != null)
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                .entity("Test with that name already exists").build());
        if (existsDeleted != null)
        {
            deleteDeletedTestForever(user, user.getEmail(), name);
        }

        final Test test = Test.createTest(userEmail, name, yaml);

        return saveTestAndBuildResponse(user, test, uri -> Response.created(uri));
    }

    public static class TestForValidation
    {
        @JsonProperty
        public Map<String, Object> params;
        @JsonProperty
        public String jsonParams;
        @JsonProperty
        public String yamlParams;
        @JsonProperty
        public String testYaml;
    }

    @POST
    @Path("validate/api")
    @Timed
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public Response validateTestApi(@Auth User user, TestForValidation testForValidation) throws IOException
    {
        final Test test = Test.createTest(user.getEmail(), "test_to_be_validated_but_not_saved",
            testForValidation.testYaml);

        Map<String, Object> params = testForValidation.params;

        if (testForValidation.jsonParams != null)
        {
            params = new ObjectMapper().readerFor(Map.class).readValue(testForValidation.jsonParams);
        }
        else if (testForValidation.yamlParams != null)
        {
            params = loadYaml(testForValidation.yamlParams);
        }

        String expandedDefinition;
        try
        {
            expandedDefinition = activeTestRunFactory.validateTestDefinition(test, userCredentialsFactory, params);
        }
        catch (Exception e)
        {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                .entity(ExceptionUtils.getStackTrace(e)).build());
        }

        return Response.ok().entity(expandedDefinition).build();
    }

    @PUT
    @Path("{name:" + NAME_PATTERN + "}/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes("application/yaml")
    public Response createOrUpdateTestApi(@Auth User user, @PathParam("name") String name, String yaml)
    {
        Test test = testDAO.get(user.getEmail(), name);
        if (test == null)
            return createTestApi(user, name, yaml);

        test.setDefinition(yaml);

        return saveTestAndBuildResponse(user, test, uri -> Response.ok(uri));
    }

    private BiPredicate<Optional<User>, Test> createCanDeletePredicate()
    {
        final var hasUnfinishedTestRuns = testRunner.createHasUnfinishedTestRunsPredicate();
        return (user, test) -> authorizedToDelete(user, test) && !hasUnfinishedTestRuns.test(test);
    }

    private boolean authorizedToDelete(Optional<User> user, Test test)
    {
        return user.map(u -> u.isAdmin() || test.isOwnedBy(u)).orElse(false);
    }

    @DELETE
    @Path("{name: " + NAME_PATTERN + "}/api")
    @Timed
    public Response deleteTestForUserApi(@Auth User user, @PathParam("name") String name,
        @QueryParam("deleteforever") @DefaultValue("false") Boolean deleteForever)
    {
        return deleteTestApi(user, user.getEmail(), name, deleteForever);
    }

    @DELETE
    @Path("{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/api")
    @Timed
    public Response deleteTestApi(@Auth User user, @PathParam("userEmail") String userEmail,
        @PathParam("name") String name, @QueryParam("deleteforever") @DefaultValue("false") Boolean deleteForever)
    {
        Test test = testDAO.get(userEmail, name);

        // Delete test succeeds if the test doesn't exist
        if (test == null)
        {
            return Response.status(Response.Status.OK).build();
        }

        if (testRunner.createHasUnfinishedTestRunsPredicate().test(test))
        {
            return Response.status(Response.Status.CONFLICT)
                .entity("Cannot delete a test with unfinished test runs").build();
        }

        if (!authorizedToDelete(Optional.of(user), test))
        {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        List<PerformanceReport> perfReports = reportDAO.getAll().stream()
            .filter(perfReport -> perfReport.getReportTestRuns() != null &&
                perfReport.getReportTestRuns().stream()
                    .filter(Objects::nonNull)
                    .anyMatch(tri -> tri.getTestName().equals(name) && tri.getTestOwner().equals(userEmail)))
            .collect(Collectors.toList());

        if (!perfReports.isEmpty())
        {
            Map<String, String> reportNameEmailPairs = new HashMap<>();
            for (PerformanceReport perfReport : perfReports)
            {
                String report = perfReport.getReportName();
                String email = perfReport.getEmail();
                reportNameEmailPairs.put(report, email);
            }
            logger.info("User tried to delete test, but it is in use by the following performance reports: " +
                reportNameEmailPairs);
            throw new WebApplicationException(
                "Cannot delete test while being used by any Performance report(s): " + reportNameEmailPairs,
                Response.Status.FORBIDDEN);
        }
        try
        {
            if (!deleteForever)
            {
                testRunDAO.dropAll(userEmail, name);
                testDAO.drop(userEmail, name);
            }
            else
            {
                testDAO.delete(userEmail, name);
                testRunDAO.deleteAll(userEmail, name);
            }
        }
        catch (Exception e)
        {
            logger.error("Failed to delete test: " + name, e);
            throw new WebApplicationException("Could not delete test!", Response.Status.INTERNAL_SERVER_ERROR);
        }

        return Response.ok().build();
    }

    @POST
    @Path("/deleted/{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/restore/api")
    @Timed
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response restoreTest(@Auth User user, @PathParam("userEmail") String userEmail,
        @PathParam("name") String name)
    {
        DeletedTest deletedTest = testDAO.getDeleted(userEmail, name);

        if (deletedTest == null)
        {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (!authorizedToDelete(Optional.of(user), deletedTest))
        {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        try
        {
            testDAO.restore(deletedTest);
            testRunDAO.restoreAll(userEmail, name);
        }
        catch (Exception e)
        {
            logger.error("Failed to restore test: " + name, e);
            throw new WebApplicationException("Could not restore test!", Response.Status.INTERNAL_SERVER_ERROR);
        }

        return Response.ok().build();
    }

    @DELETE
    @Path("/deleted/{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteDeletedTestForever(@Auth User user, @PathParam("userEmail") String userEmail,
        @PathParam("name") String name)
    {
        DeletedTest deletedTest = testDAO.getDeleted(userEmail, name);

        if (deletedTest == null)
        {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (!authorizedToDelete(Optional.of(user), deletedTest))
        {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        try
        {
            testDAO.delete(userEmail, name);
            testRunDAO.deleteAll(userEmail, name);
        }
        catch (Exception e)
        {
            logger.error("Failed to delete test: " + name, e);
            throw new WebApplicationException("Could not delete test forever.", Response.Status.INTERNAL_SERVER_ERROR);
        }

        return Response.ok().build();
    }

    @GET
    @Path("{name: " + NAME_PATTERN + "}/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public Test getTestForUserApi(@Auth User user, @PathParam("name") String name)
    {
        return getTestApi(user.getEmail(), name);
    }

    @GET
    @Path("{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public Test getTestApi(@PathParam("userEmail") String userEmail, @PathParam("name") String name)
    {
        Test test = testDAO.get(userEmail, name);
        assertTest(test);
        return test;
    }

    @GET
    @Path("deleted/{name: " + NAME_PATTERN + "}/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public Test getDeletedTestForUserApi(@Auth User user, @PathParam("name") String name)
    {
        return getDeletedTestApi(user.getEmail(), name);
    }

    @GET
    @Path("deleted/{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public Test getDeletedTestApi(@PathParam("userEmail") String userEmail, @PathParam("name") String name)
    {
        Test deletedTest = testDAO.getDeleted(userEmail, name);
        assertTest(deletedTest);
        return deletedTest;
    }

    @GET
    @Path("{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/{name2: " + NAME_PATTERN + "}.yaml")
    @Timed
    @Produces("application/yaml")
    public Response getTestdefinition(@PathParam("userEmail") String userEmail, @PathParam("name") String name,
        @PathParam("name2") String name2)
    {
        if (!("definition".equals(name2) || name.equals(name2)))
            throw new WebApplicationException(Response.Status.NOT_FOUND);

        Test test = testDAO.get(userEmail, name);
        assertTest(test);

        return Response.ok(test.getDefinition().replace("\\n", "\n"))
            .header("Content-Type", "text/yaml")
            .header("Content-Disposition", "inline; filename=\"" + name + ".yaml\"")
            .build();
    }

    @GET
    @Path("/ui/{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/{testRunId:" + ID_PATTERN + "}/yaml")
    @Timed
    @Produces("application/yaml")
    public Response getTemplatedTestRunDefinition(@Auth Optional<User> user, @PathParam("userEmail") String email,
        @PathParam("name") String name, @PathParam("testRunId") String testRunId)
    {
        return getTestRunDefinition(email, name, testRunId, TestRun::getDefinitionWithTemplateParams);
    }

    @GET
    @Path("/{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/{testRunId:" + ID_PATTERN +
        "}/expanded-yaml/api")
    @Timed
    @Produces("application/yaml")
    public Response getExpandedTestRunDefinition(@Auth Optional<User> user, @PathParam("userEmail") String email,
        @PathParam("name") String name, @PathParam("testRunId") String testRunId)
    {
        return getTestRunDefinition(email, name, testRunId, TestRun::getExpandedDefinition);
    }

    private Response getTestRunDefinition(String email, String testName, String testRunId,
        Function<TestRun, String> definitionMethod)
    {
        TestRun testRun = testRunDAO.getEvenIfDeleted(email, testName, UUID.fromString(testRunId));
        assertTestRun(testRun);

        if (testRun.getDefinition() == null)
            throw new WebApplicationException(String.format("No YAML found for run %s", testRunId),
                Response.Status.NOT_FOUND);

        return Response.ok(definitionMethod.apply(testRun).replace("\\n", "\n"))
            .header("Content-Type", "text/yaml")
            .header("Content-Disposition", "inline; filename=\"" + testName + ".yaml\"")
            .build();
    }

    @GET
    @Path("api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public List<Test> listTestsApi(@Auth User user)
    {
        return listTestsForUserApi(user.getEmail());
    }

    @GET
    @Path("{userEmail: " + EMAIL_PATTERN + "}/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public List<Test> listTestsForUserApi(@PathParam("userEmail") String userEmail)
    {
        List<Test> tests = testDAO.getAll(userEmail);
        assertTests(tests);
        return tests;
    }

    @GET
    @Path("/search")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public List<Test> searchTestsApi(@Auth User user, @QueryParam("testName") String testName,
        @QueryParam("owner") String owner,
        @QueryParam("tags") List<String> tags, @QueryParam("created") String created)
    {
        if (tags == null)
            throw new WebApplicationException(Response.Status.BAD_REQUEST);

        Date dateCreated = (created == null) ?
            null : Date.from(LocalDate.parse(created).atStartOfDay(ZoneId.systemDefault()).toInstant());

        List<Test> result = testDAO.getTestsFromTag(tags);
        result = result.stream()
            .filter(t -> testName == null || t.getName().equals(testName))
            .filter(t -> owner == null || t.getOwner().equals(owner))
            .filter(t -> dateCreated == null || t.getCreatedAt().after(dateCreated))
            .collect(Collectors.toList());

        return result;
    }

    public static class CreateTestRun
    {
        public TestCompletionNotification emailPref;
        public TestCompletionNotification slackPref;
        public Map<String, Object> jsonTemplateParams;
        public String yamlTemplateParams;
    }

    @POST
    @Path("{name: " + NAME_PATTERN + "}/runs/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createTestRunApi(@Auth User user, @PathParam("name") String name, @Context UriInfo uriInfo,
        CreateTestRun createTestRunParams)
    {
        if (createTestRunParams == null)
        {
            createTestRunParams = new CreateTestRun();
            MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
            if (!queryParams.isEmpty())
            {
                createTestRunParams.jsonTemplateParams =
                    queryParams.entrySet().stream()
                        .collect(
                            ImmutableMap.Builder<String, Object>::new,
                            (builder, entry) -> builder.put(entry.getKey(), entry.getValue().iterator().next()),
                            (builder1, builder2) -> builder1.putAll(builder2.build()))
                        .build();
            }
        }
        else if (createTestRunParams.yamlTemplateParams != null)
        {
            createTestRunParams.jsonTemplateParams =
                loadYaml(createTestRunParams.yamlTemplateParams);
        }

        if (createTestRunParams.jsonTemplateParams == null)
        {
            createTestRunParams.jsonTemplateParams = Collections.emptyMap();
        }

        final Test test = testDAO.get(user.getEmail(), name);
        assertTest(test);

        final TestRun testRun = test.createTestRun(createTestRunParams.jsonTemplateParams);

        testRun.setEmailPref(createTestRunParams.emailPref != null ?
            createTestRunParams.emailPref :
            user.getEmailPref());
        testRun.setSlackPref(createTestRunParams.slackPref != null ?
            createTestRunParams.slackPref :
            user.getSlackPref());

        testDAO.update(test);

        testRunner.queueTestRun(testRun);

        return Response.created(uriForGetTestRunApi(testRun)).entity(testRun).build();
    }

    public static class RerunParams
    {
        /** Whether to clone the testrun; if false, use the current test definition */
        @JsonProperty
        boolean clone;
    }

    @POST
    @Path("{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/runs/{testRunId: " + ID_PATTERN +
        "}/rerun/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createTestRerunApi(@Auth User user, @PathParam("userEmail") String email,
        @PathParam("name") String name,
        @PathParam("testRunId") String testRunId, RerunParams rerunParams)
    {
        if (!email.equals(user.getEmail()))
        {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }

        final TestRun testRun = testRunDAO.get(email, name, UUID.fromString(testRunId));
        assertTestRun(testRun);

        TestRun rerun;

        if (rerunParams.clone)
        {
            rerun = testRun.copyForReRun();
        }
        else
        {
            Test test = testDAO.get(testRun.getOwner(), testRun.getTestName());
            assertTest(test);
            rerun = test.createTestRun(testRun.getTemplateParamsMap());
            testDAO.update(test);
        }

        testRunner.queueTestRun(rerun);

        return Response.created(uriForGetTestRunApi(rerun)).entity(rerun).build();
    }

    @GET
    @Path("{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/runs/{testRunId: " + ID_PATTERN + "}/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public TestRun getTestRunApi(@PathParam("userEmail") String email,
        @PathParam("name") String name,
        @PathParam("testRunId") String testRunIdStr)
    {
        final UUID testRunId = UUID.fromString(testRunIdStr);
        return fetchTestRunAndMaybeUpdateArtifacts(testRunId, () -> testRunDAO.get(email, name, testRunId));
    }

    public static URI uriForGetTestRunApi(TestRun testRun)
    {
        return uriFor(TestResource.class, "getTestRunApi", testRun.getOwner(), testRun.getTestName(),
            testRun.getTestRunId());
    }

    @GET
    @Path("/runs/{testRunId: " + ID_PATTERN + "}/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public TestRun getTestRunByTestRunIdApi(@PathParam("testRunId") String testRunIdStr)
    {
        final UUID testRunId = UUID.fromString(testRunIdStr);
        return fetchTestRunAndMaybeUpdateArtifacts(testRunId, () -> testRunDAO.getByTestRunId(testRunId));
    }

    @GET
    @Path("{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/runs/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public List<TestRun> listTestRunsApi(@PathParam("userEmail") String email, @PathParam("name") String name)
    {
        return fetchTestRunsAndMaybeUpdateArtifacts(email, name, testRunDAO::getAll);
    }

    static class SimpleTestRun
    {
        public UUID testRunId;
        public String displayName;

        private SimpleTestRun(UUID testRunId, String displayName)
        {
            this.testRunId = testRunId;
            this.displayName = displayName;
        }
    }

    @GET
    @Path("{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/runs/simple/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public List<SimpleTestRun> listSimpleTestRunsApi(@PathParam("userEmail") String email,
        @PathParam("name") String name)
    {
        List<TestRun> alltestRuns = testRunDAO.getAll(email, name);
        List<TestRun> finishedTestRuns = alltestRuns.stream()
            .filter(r -> r.getState().finished())
            .sorted(Comparator.comparing(TestRun::getCreatedAt).reversed())
            .collect(Collectors.toList());
        final Map<TestRun, String> testRunDisplayNames = TestRunUtils.buildTestRunDisplayNames(finishedTestRuns, false);

        List<SimpleTestRun> simpleTestRuns = finishedTestRuns.stream()
            .map(r -> new SimpleTestRun(r.getTestRunId(), testRunDisplayNames.get(r))).collect(
                Collectors.toList());
        return simpleTestRuns;
    }

    @GET
    @Path("{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/runs/newest/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public TestRun getNewestTestRunApi(@PathParam("userEmail") String email, @PathParam("name") String name)
    {
        List<TestRun> testRuns = assertTestRuns(testRunDAO.getAll(email, name));
        testRuns.sort(Comparator.comparing(TestRun::getCreatedAt).reversed()); // Sort by newest to oldest

        return testRuns.get(0); // Grab first index, should be the newest started run
    }

    public static boolean canCancel(Optional<User> user, ReadOnlyTestRun testRun)
    {
        if (testRun == null || testRun.getState().finished())
        {
            return false;
        }
        return authorizedToCancel(user, testRun);
    }

    public static boolean canDelete(Optional<User> user, ReadOnlyTestRun testRun)
    {
        if (testRun == null || !testRun.getState().finished())
        {
            return false;
        }
        return authorizedToDelete(user, testRun);
    }

    public static boolean authorizedToCancel(Optional<User> user, ReadOnlyTestRun testRun)
    {
        return user.map(u -> u.isAdmin() || testRun.isOwnedBy(u)).orElse(false);
    }

    public static boolean authorizedToDelete(Optional<User> user, ReadOnlyTestRun testRun)
    {
        return user.map(u -> u.isAdmin() || testRun.isOwnedBy(u))
            .orElse(false);
    }

    @POST
    @Path("{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/runs/{testRunId: " + ID_PATTERN +
        "}/abort/api")
    @Timed
    public Response abortTestRun(
        @Auth User user, @PathParam("userEmail") String userEmail, @PathParam("name") String testName,
        @PathParam("testRunId") String testRunId)
    {
        TestRun testRun = testRunDAO.get(userEmail, testName, UUID.fromString(testRunId));

        if (testRun == null)
        {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (testRun.getState().finished())
        {
            return Response.notModified().build();
        }

        if (!authorizedToCancel(Optional.of(user), testRun))
        {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        logger.info("{}'s test run {} of {} was aborted by {}", userEmail, testRunId, testName, user.getEmail());
        if (testRunner.abortTestRun(testRun))
        {
            return Response.ok().build();
        }
        else
        {
            return Response.notModified().build();
        }
    }

    public static URI uriForAbortTestRun(TestRun testRun)
    {
        return uriFor(TestResource.class, "abortTestRun", testRun.getOwner(), testRun.getTestName(),
            testRun.getTestRunId());
    }

    @DELETE
    @Path("{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/runs/{testRunId: " + ID_PATTERN + "}/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteTestRun(@Auth User user, @PathParam("userEmail") String userEmail,
        @PathParam("name") String testName,
        @PathParam("testRunId") String testRunId)
    {
        TestRun testRun = testRunDAO.get(userEmail, testName, UUID.fromString(testRunId));

        if (testRun == null)
        {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (!testRun.getState().finished())
        {
            return Response.status(Response.Status.CONFLICT)
                .entity("Cannot delete an unfinished test run").build();
        }

        if (!authorizedToDelete(Optional.of(user), testRun))
        {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        List<PerformanceReport> perfReports = reportDAO.getAll().stream()
            .filter(perfReport -> perfReport.getReportTestRuns() != null &&
                perfReport.getReportTestRuns().stream()
                    .filter(Objects::nonNull)
                    .anyMatch(tri -> tri.getTestRunId().equals(UUID.fromString(testRunId))))
            .collect(Collectors.toList());

        if (!perfReports.isEmpty())
        {
            Map<String, String> reportNameEmailPairs = new HashMap<>();
            for (PerformanceReport perfReport : perfReports)
            {
                String report = perfReport.getReportName();
                String email = perfReport.getEmail();
                reportNameEmailPairs.put(report, email);

            }

            logger.info("User tried to delete test run, being used by the following Performance report(s): " +
                reportNameEmailPairs);
            throw new WebApplicationException(
                "Cannot delete test run, being used by the following Performance report(s): " + reportNameEmailPairs,
                Response.Status.FORBIDDEN);
        }

        try
        {
            testRunDAO.drop(userEmail, testName, UUID.fromString(testRunId));
            testDAO.changeSizeOnDiskBytes(testRun, -testRun.getArtifactsSizeBytes().orElse(0L));
        }
        catch (Exception e)
        {
            logger.error("Error while deleting Test Run: " + testRunId, e);
            return Response.serverError().build();
        }

        return Response.ok().build();
    }

    @POST
    @Path("deleted/{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/runs/{testRunId: " + ID_PATTERN +
        "}/restore/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public Response restoreTestRun(@Auth User user, @PathParam("userEmail") String userEmail,
        @PathParam("name") String testName, @PathParam("testRunId") String testRunId)
    {
        DeletedTest deletedTest = testDAO.getDeleted(userEmail, testName);
        DeletedTestRun deletedTestRun = testRunDAO.getDeleted(userEmail, testName, UUID.fromString(testRunId));
        try
        {
            testRunDAO.restore(deletedTestRun);
            if (deletedTest != null)
            {
                deletedTest.setSizeOnDiskBytes(0L);
                testDAO.restore(deletedTest);
            }
            testDAO.changeSizeOnDiskBytes(deletedTestRun);
        }
        catch (Exception e)
        {
            logger.error("Error while restoring Test Run: " + testRunId, e);
            return Response.serverError().build();
        }

        return Response.ok().build();
    }

    @GET
    @Path("deleted/{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/runs/{testRunId: " + ID_PATTERN +
        "}/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public TestRun getDeletedTestRunApi(@Auth User user, @PathParam("userEmail") String userEmail,
        @PathParam("name") String testName,
        @PathParam("testRunId") String testRunId)
    {
        TestRun deletedTestRun = testRunDAO.getDeleted(userEmail, testName, UUID.fromString(testRunId));
        assertTestRun(deletedTestRun);

        return deletedTestRun;
    }

    public static class ComponentView extends FalloutView
    {
        final String prefix;
        final String title;
        final List<ComponentCategories> components;
        final String defaultTxt;
        final String cssClass;

        public ComponentView(User user, String prefix, String title, String defaultTxt, String cssClass,
            List<? extends PropertyBasedComponent> components, MainView mainView)
        {
            super("component.mustache", user, mainView);

            this.title = title;
            this.defaultTxt = defaultTxt;
            this.prefix = prefix;
            this.cssClass = cssClass;
            this.components = components.stream()
                .map(c -> new ComponentCategories(c))
                .collect(Collectors.toList());
        }

        public static class ComponentCategories
        {
            final PropertyBasedComponent component;
            final Collection<Category> componentCategories;

            ComponentCategories(PropertyBasedComponent component)
            {
                this.component = component;
                this.componentCategories = Category.getCategories(component, component.getPropertySpecs());
            }

            public String getName()
            {
                return this.component.name().toLowerCase();
            }
        }
    }

    @GET
    @Path("/tools/admin")
    @Timed
    @Produces(MediaType.TEXT_HTML)
    public FalloutView getAdmin(@Auth User user)
    {
        return new TestAdminView(user, testRunner);
    }

    private static void requireAdminTo(User user, String operationDescription)
    {
        if (!user.isAdmin())
        {
            throw new WebApplicationException("You must be an admin to " + operationDescription,
                Response.Status.FORBIDDEN);
        }
    }

    @POST
    @Path("/requestShutdown/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public void requestShutdownAPI(@Auth User user)
    {
        requireAdminTo(user, "request shutdown");
        queueAdminTask.requestShutdown(user.getEmail());
    }

    @POST
    @Path("/cancelShutdown/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public void cancelShutdown(@Auth User user)
    {
        requireAdminTo(user, "cancel shutdown");
        queueAdminTask.cancelShutdown();
    }

    @POST
    @Path("/abortAndRequeueRunningTestRuns/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public List<TestRun> abortAndRequeueRunningTestRuns(@Auth User user)
    {
        requireAdminTo(user, "abort and requeue running tests");
        if (!testRunner.isShutdownRequested())
        {
            throw new WebApplicationException("A shutdown must be requested first", Response.Status.FORBIDDEN);
        }
        if (testRunner.testsHaveBeenAbortedAndRequeued())
        {
            throw new WebApplicationException("Abort and requeue should only be used once while a shutdown is " +
                "requested", Response.Status.FORBIDDEN);
        }

        return testRunner.abortAndRequeueRunningTestRuns();
    }

    @PUT
    @Path("{name:" + NAME_PATTERN + "}/tag/api")
    @Timed
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tagTest(@Auth User user, @PathParam("name") String testName, List<String> tags)
    {
        Test test = testDAO.get(user.getEmail(), testName);
        assertTest(test);

        test.setTags(new HashSet<>(tags));
        testDAO.add(test);

        return Response.ok().build();
    }

    @GET
    @Path("{name:" + NAME_PATTERN + "}/tag/api")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public Set<String> getTestTags(@Auth User user, @PathParam("name") String testName)
    {
        Test test = testDAO.get(user.getEmail(), testName);
        assertTest(test);

        return test.getTags();
    }

    @GET
    @Path("/tools/create")
    @Timed
    @Produces(MediaType.TEXT_HTML)
    public FalloutView createTest(@Auth User user, @QueryParam("email") String email, @QueryParam("name") String name)
    {
        Test test = null;

        if (email != null && name != null)
            test = testDAO.getEvenIfDeleted(email, name);

        return new TestBuilderView(user, test);
    }

    @GET
    @Path("/tools/edit")
    @Timed
    @Produces(MediaType.TEXT_HTML)
    public FalloutView editTest(@Auth User user, @QueryParam("email") String email, @QueryParam("name") String name)
    {
        Test test = null;

        if (email != null && name != null)
            test = testDAO.get(email, name);

        assertTest(test);

        return new TestBuilderView(user, test, true);
    }

    public class TestBuilderView extends FalloutView
    {
        final Test test;
        final boolean edit;
        final PropertySpec<NodeGroup.State> runLevelSpec =
            FalloutPropertySpecs.launchRunLevelPropertySpec;
        final String defaultTestDefinition =
            Utils.getResource(this, "default-test-definition.yaml")
                .map(byteArray -> new String(byteArray, StandardCharsets.UTF_8))
                .orElse("");

        public TestBuilderView(User user, Test test)
        {
            this(user, test, false);
        }

        public TestBuilderView(User user, Test test, boolean edit)
        {
            super("test-create.mustache", user, mainView);
            this.test = test;
            this.edit = edit;
        }
    }

    @POST
    @Path("/tools/create")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public Response checkTest(@Auth User user,
        @FormParam("ensemble.server.cluster.size") Integer serverNodes,
        @FormParam("ensemble.client.cluster.size") Integer clientNodes,
        @FormParam("ensemble.server.cluster.runlevel") String serverRunLevel,
        @FormParam("ensemble.client.cluster.runlevel") String clientRunLevel,
        Form formData)
    {

        if (serverNodes == null)
            return FalloutView.error("Number of nodes required for ensemble server group");

        if (serverNodes <= 0 || serverNodes >= 1024)
            return FalloutView.error("Invalid number of nodes specified for ensemble server group");

        MultivaluedMap<String, String> formMap = formData.asMap();

        try
        {

            //Server group
            WritablePropertyGroup serverProvisionerProperties = new WritablePropertyGroup();
            Provisioner serverProvisioner = getComponentAndProperties(formMap, "Server Provisioner", "server-prv",
                serverProvisionerProperties, Provisioner.class);

            List<Pair<PropertyBasedComponent, PropertyGroup>> serverCMList = new ArrayList<>();
            for (int i = 1; i < 10; i++)
            {
                String cmType = formMap.getFirst(String.format("server-cm%d-type", i));
                if (cmType == null || cmType.isEmpty())
                    continue;

                WritablePropertyGroup properties = new WritablePropertyGroup();
                ConfigurationManager cm = getComponentAndProperties(formMap, "Server Configuration Manager #" + i,
                    "server-cm" + i, properties, ConfigurationManager.class);

                serverCMList.add(Pair.of(cm, properties));
            }

            if (serverCMList.isEmpty())
                return FalloutView.error("Server Configuration Manager Required");

            //Client group
            WritablePropertyGroup clientProvisionerProperties = null;
            Provisioner clientProvisioner = null;
            List<Pair<PropertyBasedComponent, PropertyGroup>> clientCMList = null;

            //Special check for Client Provisioner
            if (!formMap.getFirst("client-prv-type").isEmpty())
            {

                if (clientNodes == null)
                    return FalloutView.error("Number of nodes required for ensemble client group");

                if (clientNodes <= 0 || clientNodes >= 1024)
                    return FalloutView.error("Invalid number of nodes specified for ensemble server group");

                clientProvisionerProperties = new WritablePropertyGroup();
                clientProvisioner = getComponentAndProperties(formMap, "Client Provisioner", "client-prv",
                    clientProvisionerProperties, Provisioner.class);

                clientCMList = new ArrayList<>();
                for (int i = 1; i < 10; i++)
                {
                    String cmType = formMap.getFirst(String.format("client-cm%d-type", i));
                    if (cmType == null || cmType.isEmpty())
                        continue;

                    WritablePropertyGroup properties = new WritablePropertyGroup();
                    ConfigurationManager cm = getComponentAndProperties(formMap, "Client Configuration Manager #" + i,
                        "client-cm" + i, properties, ConfigurationManager.class);

                    clientCMList.add(Pair.of(cm, properties));
                }

                if (clientCMList.isEmpty())
                    return FalloutView.error("Client Configuration Manager Required");
            }

            //Phases
            List<Map<String, Pair<PropertyBasedComponent, PropertyGroup>>> phaseLists = new ArrayList<>();

            for (int p = 1; p < 100; p++)
            {
                Map<String, Pair<PropertyBasedComponent, PropertyGroup>> modules = new LinkedHashMap<>();
                for (int m = 1; m < 100; m++)
                {
                    String prefix = String.format("phase%d-mod%d", p, m);
                    String modName = formMap.getFirst(prefix);
                    if (modName == null || modName.trim().isEmpty())
                        continue;

                    if (!namePattern.matcher(modName).matches())
                        return FalloutView.error(String.format(
                            "Phase #%d, Module #%d name must be alphanumeric (no spaces, use ._- chars)", p, m));

                    WritablePropertyGroup properties = new WritablePropertyGroup();
                    Module module = getComponentAndProperties(formMap,
                        String.format("Phase #%d Module '%s'", p, modName), prefix, properties, Module.class);

                    if (modules.put(modName, Pair.of(module, properties)) != null)
                        return FalloutView
                            .error(String.format("Duplicate module name detected in Phase #%d: %s", p, modName));
                }

                if (!modules.isEmpty())
                    phaseLists.add(modules);
            }

            if (phaseLists.isEmpty())
                return FalloutView.error("At least one Phase required");

            //Checkers (optional)
            Map<String, Pair<PropertyBasedComponent, PropertyGroup>> checkerList = new LinkedHashMap<>();
            for (int c = 0; c < 100; c++)
            {
                String prefix = String.format("checker%d", c);

                String checkerName = formMap.getFirst(prefix);
                if (checkerName == null || checkerName.trim().isEmpty())
                    continue;

                if (!namePattern.matcher(checkerName).matches())
                    return FalloutView
                        .error(String.format("Checker #%d name must be alphanumeric (no spaces, use ._- chars)", c));

                String checkerType = formMap.getFirst(String.format("checker%d-type", c));
                if (checkerType == null || checkerType.trim().isEmpty())
                    return FalloutView.error("Checker type missing for: " + checkerName);

                WritablePropertyGroup properties = new WritablePropertyGroup();
                Checker checker =
                    getComponentAndProperties(formMap, "Checker " + checkerName, prefix, properties, Checker.class);

                if (checkerList.put(checkerName, Pair.of(checker, properties)) != null)
                    return FalloutView.error("Duplicate checker name detected: " + checkerName);
            }

            // Artifact Checkers (optional)
            Map<String, Pair<PropertyBasedComponent, PropertyGroup>> artifactCheckerList = new LinkedHashMap<>();
            for (int c = 0; c < 100; c++)
            {
                String prefix = String.format("artifact_checker%d", c);

                String artifactCheckerName = formMap.getFirst(prefix);
                if (artifactCheckerName == null || artifactCheckerName.trim().isEmpty())
                    continue;

                if (!namePattern.matcher(artifactCheckerName).matches())
                    return FalloutView.error(
                        String.format("Artifact Checker #%d name must be alphanumeric (no spaces, use ._- chars)", c));

                String artifactCheckerType = formMap.getFirst(String.format("artifact_checker%d-type", c));
                if (artifactCheckerType == null || artifactCheckerType.trim().isEmpty())
                    return FalloutView.error("Artifact Checker type missing for: " + artifactCheckerName);

                WritablePropertyGroup properties = new WritablePropertyGroup();
                ArtifactChecker checker = getComponentAndProperties(formMap, "Artifact Checker " + artifactCheckerName,
                    prefix, properties, ArtifactChecker.class);

                if (artifactCheckerList.put(artifactCheckerName, Pair.of(checker, properties)) != null)
                    return FalloutView.error("Duplicate artifact_checker name detected: " + artifactCheckerName);
            }

            String yaml = buildYaml(serverNodes, serverRunLevel, serverProvisioner, serverProvisionerProperties,
                serverCMList, clientNodes, clientRunLevel, clientProvisioner, clientProvisionerProperties,
                clientCMList, phaseLists, checkerList, artifactCheckerList);

            return Response.ok().entity(ImmutableMap.of("yaml", yaml)).build();
        }
        catch (InstantiationException | IllegalAccessException | JsonProcessingException e)
        {
            throw new WebApplicationException(e.getMessage());
        }
        catch (IllegalArgumentException e)
        {
            return FalloutView.error(e.getMessage());
        }
    }

    private StringBuilder space(StringBuilder sb, int spaces)
    {
        for (int i = 0; i < spaces; i++)
            sb.append(" ");

        return sb;
    }

    private StringBuilder addComponentYaml(StringBuilder sb, int offset, String title, String type,
        PropertyBasedComponent component, PropertyGroup properties) throws JsonProcessingException
    {
        space(sb, offset).append(title).append(":\n");
        space(sb, offset + 4).append(type).append(": ").append(component.name()).append("\n");

        String prefix = component.prefix();
        if (!properties.isEmpty())
        {
            space(sb, offset + 4).append("properties:\n");

            for (Map.Entry<String, Object> entry : properties.getProperties().entrySet())
            {
                space(sb, offset + 6).append(entry.getKey().replace(prefix, "")).append(": ")
                    .append(entry.getValue() instanceof String ? entry.getValue() :
                        yamlMapper.writeValueAsString(entry.getValue()))
                    .append("\n");
            }
        }

        return sb;
    }

    private StringBuilder addComponentYaml(StringBuilder sb, boolean isInArray, int offset, String title, String type,
        List<Pair<PropertyBasedComponent, PropertyGroup>> list) throws JsonProcessingException
    {
        space(sb, offset);

        if (isInArray)
            sb.append("- ");

        sb.append(title).append(":\n");

        for (Pair<PropertyBasedComponent, PropertyGroup> item : list)
        {
            String prefix = item.getLeft().prefix();
            space(sb, offset + 2).append("- ").append(type).append(": ").append(item.getLeft().name()).append("\n");

            if (!item.getRight().isEmpty())
            {
                space(sb, offset + 4).append("properties:\n");

                for (Map.Entry<String, Object> entry : item.getRight().getProperties().entrySet())
                {
                    space(sb, offset + 6).append(entry.getKey().replace(prefix, "")).append(": ")
                        .append(entry.getValue() instanceof String ? entry.getValue() :
                            yamlMapper.writeValueAsString(entry.getValue()))
                        .append("\n");
                }
            }
        }

        return sb;
    }

    private String buildYaml(Integer serverNodes, String serverRunLevel, Provisioner serverProvisioner,
        PropertyGroup serverProvisionerProperties, List<Pair<PropertyBasedComponent, PropertyGroup>> serverCmList,
        Integer clientNodes, String clientRunLevel, Provisioner clientProvisioner,
        PropertyGroup clientProvisionerProperties, List<Pair<PropertyBasedComponent, PropertyGroup>> clientCmList,
        List<Map<String, Pair<PropertyBasedComponent, PropertyGroup>>> phaseLists,
        Map<String, Pair<PropertyBasedComponent, PropertyGroup>> checkerList,
        Map<String, Pair<PropertyBasedComponent, PropertyGroup>> artifactCheckerList) throws JsonProcessingException
    {
        String space = "  ";
        StringBuilder sb = new StringBuilder();

        //Ensemble Section
        sb.append("#\n# Test ensemble\n#\n");
        sb.append("ensemble:\n");

        //Server Section
        sb.append(space).append("server:\n");
        space(sb, 4).append("node.count: ").append(serverNodes).append("\n");
        space(sb, 4).append("runlevel: ").append(serverRunLevel).append("\n");
        addComponentYaml(sb, 4, "provisioner", "name", serverProvisioner, serverProvisionerProperties);
        addComponentYaml(sb, false, 4, "configuration_manager", "name", serverCmList);
        sb.append("\n");

        //Client Section
        if (clientProvisioner == null)
        {
            sb.append(space).append("client: server");
        }
        else
        {
            sb.append(space).append("client:\n");
            space(sb, 4).append("node.count: ").append(clientNodes).append("\n");
            space(sb, 4).append("runlevel: ").append(clientRunLevel).append("\n");
            addComponentYaml(sb, 4, "provisioner", "name", clientProvisioner, clientProvisionerProperties);
            addComponentYaml(sb, false, 4, "configuration_manager", "name", clientCmList);
        }

        //Workload section
        sb.append("\n#\n# Test workload\n#\n");
        sb.append("workload:\n");
        sb.append(space).append("phases:\n");

        for (Map<String, Pair<PropertyBasedComponent, PropertyGroup>> phase : phaseLists)
        {
            boolean first = true;
            for (Map.Entry<String, Pair<PropertyBasedComponent, PropertyGroup>> entry : phase.entrySet())
            {
                addComponentYaml(sb, (first ? 4 : 6), ((first ? "- " : "") + entry.getKey()), "module",
                    entry.getValue().getLeft(), entry.getValue().getRight());
                first = false;
            }
        }

        if (!checkerList.isEmpty())
        {
            sb.append(space).append("checkers:\n");

            for (Map.Entry<String, Pair<PropertyBasedComponent, PropertyGroup>> entry : checkerList.entrySet())
                addComponentYaml(sb, 4, entry.getKey(), "checker", entry.getValue().getLeft(),
                    entry.getValue().getRight());
        }

        if (!artifactCheckerList.isEmpty())
        {
            sb.append(space).append("artifact_checkers:\n");

            for (Map.Entry<String, Pair<PropertyBasedComponent, PropertyGroup>> entry : artifactCheckerList.entrySet())
                addComponentYaml(sb, 4, entry.getKey(), "artifact_checker", entry.getValue().getLeft(),
                    entry.getValue().getRight());
        }

        return sb.toString();
    }

    public static <T extends PropertyBasedComponent> T getComponentAndProperties(MultivaluedMap<String, String> form,
        String title, String prefix, WritablePropertyGroup properties, Class<T> clazz)
        throws IllegalAccessException, InstantiationException, IllegalArgumentException
    {
        List<T> types = Utils.loadComponents(clazz);

        String typeName = form.getFirst(prefix + "-type");

        if (typeName == null || typeName.trim().isEmpty())
            throw new IllegalArgumentException(String.format("%s must be selected", title));

        Optional<T> foundType = types.stream().filter(t -> t.name().equalsIgnoreCase(typeName.trim())).findFirst();

        if (!foundType.isPresent())
            throw new IllegalArgumentException(String.format("Unknown name for %s : %s", title, typeName));

        T type = foundType.get();

        for (PropertySpec p : type.getPropertySpecs())
        {
            List<String> values = form.get(String.format("%s-prop-%s", prefix, p.name()));

            if (values == null || values.isEmpty())
                continue;

            if (values.size() > 1)
                properties.put(p.name(), values);

            String val = values.get(0);

            if (val.trim().length() > 0)
            {
                //skip over defaults
                if (p.defaultValue().isPresent())
                {
                    if (((PropertySpec.Value<?>) p.defaultValue().get()).value.toString().equals(val))
                        continue;
                }

                properties.put(p.name(), val);
            }
        }

        try
        {
            properties.validate(type.getPropertySpecs(), true);
        }
        catch (PropertySpec.ValidationException e)
        {
            throw new IllegalArgumentException(String.format("%s problem: %s", title, e.getMessage()));
        }

        return type;
    }

    public static class Category
    {
        final String category;
        final String parentPropertyName;
        final List<PropertySpec> properties;
        final Object owner;

        Category(Object owner, String parentPropertyName, String category, List<PropertySpec> properties)
        {
            this.owner = owner;
            this.category = category;
            this.parentPropertyName = parentPropertyName;
            this.properties = properties;
        }

        boolean isDependent()
        {
            for (PropertySpec p : properties)
                if (!p.dependsOn().isPresent())
                    return false;

            return true;
        }

        private void add(PropertySpec spec)
        {
            properties.add(spec);
        }

        public static Collection<Category> getCategories(Object owner, List<PropertySpec> specs)
        {
            Map<String, Map<String, Category>> parentMap = new LinkedHashMap<>();

            for (PropertySpec<?> s : specs)
            {
                //Filter out any properties with system aliases we use on this page
                if (s.alias().isPresent() && s.alias().get().startsWith(FalloutPropertySpecs.prefix))
                    continue;

                //Filter out any properties with the internal category
                if (s.category().isPresent() && s.category().get().equals("internal"))
                    continue;

                String parent = s.dependsOn().isPresent() ? s.dependsOn().get().name() : "";
                String category = s.category().isPresent() ? s.category().get() : "";
                Map<String, Category> categoryMap = parentMap.computeIfAbsent(parent, k -> new LinkedHashMap<>());
                Category c =
                    categoryMap.computeIfAbsent(category, c1 -> new Category(owner, parent, c1, Lists.newArrayList()));
                c.add(s);
            }

            return parentMap.values().stream().flatMap(m -> m.values().stream())
                .sorted((c1, c2) -> c1.isDependent() ? c2.isDependent() ? 0 : 1 : c2.isDependent() ? -1 : 0)
                .collect(Collectors.toList());
        }
    }

    @GET
    @Path("/ui/{userEmail: " + EMAIL_PATTERN + "}")
    @Timed
    @Produces(MediaType.TEXT_HTML)
    public FalloutView showTests(@Auth Optional<User> user, @PathParam("userEmail") String email)
    {
        List<Test> tests = testDAO.getAll(email);

        tests.sort(
            Comparator.comparing((Test test) -> test.getLastRunAt() != null ? test.getLastRunAt() : test.getCreatedAt())
                .reversed());
        return new TestView(user, email, tests, testRunner);
    }

    public static String linkForShowTests(Test test)
    {
        return linkForShowTests(test.getOwner());
    }

    public static String linkForShowTests(ReadOnlyTestRun testRun)
    {
        return linkForShowTests(testRun.getOwner());
    }

    private static String linkForShowTests(String ownerEmail)
    {
        return FalloutView.linkFor(ownerEmail, TestResource.class, "showTests", ownerEmail);
    }

    @GET
    @Path("/ui/{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}")
    @Timed
    @Produces(MediaType.TEXT_HTML)
    public FalloutView showTestRuns(@Auth Optional<User> user, @PathParam("userEmail") String email,
        @PathParam("name") String name)
    {
        final Test test = testDAO.getEvenIfDeleted(email, name);
        assertTest(test);

        boolean deleted = test instanceof DeletedTest;
        final List<TestRun> testRuns = fetchTestRunsAndMaybeUpdateArtifacts(email, name, deleted ?
            testRunDAO::getAllDeleted :
            testRunDAO::getAll);

        testRuns.sort(Comparator.comparing(TestRun::getCreatedAt).reversed());

        return new TestRunView(user, email, test, testRuns, deleted);
    }

    public static String linkForShowTestRuns(Test test)
    {
        return linkForShowTestRuns(test.getOwner(), test.getName());
    }

    public static String linkForShowTestRuns(ReadOnlyTestRun testRun)
    {
        return linkForShowTestRuns(testRun.getOwner(), testRun.getTestName());
    }

    private static String linkForShowTestRuns(String ownerEmail, String testName)
    {
        return FalloutView.linkFor(testName, TestResource.class, "showTestRuns", ownerEmail, testName);
    }

    @GET
    @Path("/ui/{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/{testRunId:" + ID_PATTERN +
        "}/artifacts")
    @Timed
    @Produces(MediaType.TEXT_HTML)
    public FalloutView showTestRunArtifacts(@Auth Optional<User> user, @PathParam("userEmail") String email,
        @PathParam("name") String name, @PathParam("testRunId") String testRunIdStr)
    {
        final UUID testRunId = UUID.fromString(testRunIdStr);
        final TestRun testRun = fetchTestRunAndMaybeUpdateArtifacts(testRunId,
            () -> testRunDAO.getEvenIfDeleted(email, name, testRunId));
        return new TestDetailsView(user, name, testRun, configuration);
    }

    @GET
    @Path("/ui/runs/{testRunId:" + ID_PATTERN + "}/artifacts")
    @Timed
    public Response showTestRunArtifactsById(@PathParam("testRunId") String testRunId)
    {
        TestRun testRun = testRunDAO.getByTestRunId(UUID.fromString(testRunId));
        return Response.temporaryRedirect(uriForShowTestRunArtifacts(testRun)).build();
    }

    public static URI uriForShowTestRunArtifacts(ReadOnlyTestRun testRun)
    {
        return uriFor(TestResource.class, "showTestRunArtifacts",
            testRun.getOwner(), testRun.getTestName(), testRun.getTestRunId());
    }

    public static String linkForShowTestRunArtifacts(ReadOnlyTestRun testRun)
    {
        return FalloutView.linkFor(testRun.buildShortTestRunId(), uriForShowTestRunArtifacts(testRun));
    }

    @GET
    @Path("/ui/{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/{testRunId:" + ID_PATTERN +
        "}/live-artifacts/{artifactPath: .*\\.log}")
    @Timed
    @Produces(MediaType.TEXT_HTML)
    public FalloutView showTestRunLiveArtifact(
        @Auth Optional<User> user, @PathParam("userEmail") String email,
        @PathParam("name") String name, @PathParam("testRunId") String testRunId,
        @PathParam("artifactPath") String artifactPath)
    {
        TestRun testRun = testRunDAO.get(email, name, UUID.fromString(testRunId));
        assertTestRun(testRun);
        return new LiveArtifactView(user, testRun, artifactPath);
    }

    class LiveArtifactView extends FalloutView
    {
        private final TestRun testRun;
        public final String artifactsLink;
        public final URI directArtifactUri;
        public final URI artifactEventStreamUri;
        public final String artifactLink;
        public final String artifactPath;

        public LiveArtifactView(Optional<User> user, TestRun testRun, String artifactPath)
        {
            super("live-artifact-view.mustache", user, mainView);
            this.testRun = testRun;
            this.artifactsLink = linkForShowTestRunArtifacts(testRun);
            URI falloutArtifactUri = UriBuilder.fromUri(uriForShowTestRunArtifacts(testRun))
                .path("{artifactPath}").build(artifactPath);
            this.directArtifactUri = configuration.useNginxToServeArtifacts() ?
                UriBuilder.fromPath(NGINX_DIRECT_ARTIFACTS_LOCATION)
                    .path(testRun.getOwner())
                    .path(testRun.getTestName())
                    .path(testRun.getTestRunId().toString())
                    .path("{artifactPath}")
                    .build(artifactPath) :
                falloutArtifactUri;
            this.artifactEventStreamUri = LiveResource.uriForArtifactEventStream(testRun, artifactPath);
            this.artifactLink = linkFor(artifactPath, falloutArtifactUri);
            this.artifactPath = artifactPath;
        }
    }

    @GET
    @Path("ui/deleted")
    @Timed
    @Produces(MediaType.TEXT_HTML)
    public FalloutView showDeletedTests(@Auth Optional<User> user)
    {
        List<DeletedTest> tests = testDAO.getAllDeleted();
        tests.sort(Comparator.comparing(Test::getName));
        List<DeletedTestRun> testRuns = testRunDAO.getAllDeleted();
        testRuns.sort(Comparator.comparing(TestRun::getTestName));
        return new TrashBinView(user, tests, testRuns);
    }

    @GET
    @Path("/ui/{userEmail: " + EMAIL_PATTERN + "}/{name: " + NAME_PATTERN + "}/{testRunId:" + ID_PATTERN +
        "}/artifactsArchive")
    @Timed
    public Response downloadArtifactsAsZip(@Auth Optional<User> user, @PathParam("userEmail") String email,
        @PathParam("name") String name, @PathParam("testRunId") String testRunId)
    {
        return Response.ok(
            (StreamingOutput) stream -> writeArtifactsAsZipToStream(stream, email, name, testRunId))
            .type("application/zip")
            .header("Content-Disposition", "attachment; filename=\"" + name + ".zip\"")
            .build();
    }

    private void writeArtifactsAsZipToStream(OutputStream outputStream, String email, String name, String testRunId)
    {
        TestRun testRun = testRunDAO.getEvenIfDeleted(email, name, UUID.fromString(testRunId));
        ZipOutputStream zipOutputStream = null;
        try
        {
            zipOutputStream = new ZipOutputStream(outputStream);
            String artifactPath = configuration.getArtifactPath();
            for (String artifact : Artifacts.findTestRunArtifacts(configuration, testRun).keySet())
            {
                java.nio.file.Path filePath = Paths.get(artifactPath, email, name, testRunId, artifact);
                // filePath is an absolute path
                // keep only the relative part so that the directory tree in the zip is identical to the one in the UI
                String pathInZipFile = filePath.toString().substring(artifactPath.length() + 1);
                zipOutputStream.putNextEntry(new ZipEntry(pathInZipFile));
                IOUtils.copy(Files.newInputStream(filePath, StandardOpenOption.READ), zipOutputStream);
                zipOutputStream.closeEntry();
            }
        }
        catch (IOException e)
        {
            logger.error("Could not create zip file", e);
            throw new WebApplicationException("Could not create zip file", Response.Status.INTERNAL_SERVER_ERROR);
        }
        finally
        {
            IOUtils.closeQuietly(zipOutputStream);
        }
    }

    private boolean updateTestRunArtifactsIfNeeded(TestRun testRun)
    {
        return testRun.updateArtifactsIfNeeded(
            () -> Exceptions.getUncheckedIO(() -> Artifacts.findTestRunArtifacts(configuration, testRun)));
    }

    private void updateTestRunArtifactsIfNeededAndWriteDB(TestRun testRun)
    {
        if (updateTestRunArtifactsIfNeeded(testRun))
        {
            testRunDAO.updateArtifactsIfNeeded(testRun);
        }
    }

    private TestRun fetchTestRunAndMaybeUpdateArtifacts(UUID testRunId, Supplier<TestRun> getFromDB)
    {
        final TestRun testRun = getFromDB.get();
        if (testRun != null)
        {
            updateTestRunArtifactsIfNeededAndWriteDB(testRun);
        }
        return assertTestRun(testRun);
    }

    private List<TestRun> fetchTestRunsAndMaybeUpdateArtifacts(String email, String testName,
        BiFunction<String, String, List<? extends TestRun>> getFromDB)
    {
        return getFromDB.apply(email, testName).stream()
            .peek(this::updateTestRunArtifactsIfNeededAndWriteDB)
            .collect(Collectors.toList());
    }

    private void assertTest(Test test)
    {
        if (test == null)
        {
            throwNotFoundException("No test found");
        }
    }

    private void assertTests(List<Test> tests)
    {
        if (tests.isEmpty())
        {
            throwNotFoundException("No tests found");
        }
    }

    private TestRun assertTestRun(TestRun testRun)
    {
        if (testRun == null)
        {
            throwNotFoundException("No test run found");
        }
        return testRun;
    }

    private List<TestRun> assertTestRuns(List<TestRun> testRuns)
    {
        if (testRuns.isEmpty())
        {
            throwNotFoundException("No test runs found");
        }
        return testRuns;
    }

    private void throwNotFoundException(String message)
    {
        throw new WebApplicationException(message, Response.Status.NOT_FOUND);
    }

    public class TestView extends FalloutView
    {
        final String email;
        final LinkedTests tests;
        final LinkedTestRuns runningTestRuns;
        final LinkedTestRuns queuedTestRuns;
        final LinkedTestRuns recentTestRuns;
        final String totalSizeOnDisk;

        public TestView(Optional<User> user, String email, List<Test> tests, QueuingTestRunner testRunner)
        {
            super("test.mustache", user, mainView);
            this.email = email;
            final List<ReadOnlyTestRun> runningTestRuns = testRunner.getRunningTestRunsOrderedByDuration();
            this.tests = new LinkedTests(user, tests, createCanDeletePredicate())
                .hide(LinkedTests.TableDisplayOption.RESTORE_ACTIONS, LinkedTests.TableDisplayOption.OWNER);
            this.runningTestRuns =
                new LinkedTestRuns(user,
                    runningTestRuns.stream()
                        .filter(testRun -> testRun.getOwner().equals(email))
                        .collect(Collectors.toList()))
                            .hide(OWNER, FINISHED_AT, RESULTS, TEMPLATE_PARAMS, RESTORE_ACTIONS, RESOURCE_REQUIREMENTS,
                                DELETE_MANY, SIZE_ON_DISK);
            this.queuedTestRuns =
                new LinkedTestRuns(user, true,
                    testRunner.getQueuedTestRuns().stream()
                        .filter(testRun -> testRun.getOwner().equals(email))
                        .collect(Collectors.toList()))
                            .hide(OWNER, FINISHED_AT, RESULTS, TEMPLATE_PARAMS, RESTORE_ACTIONS, RESOURCE_REQUIREMENTS,
                                DELETE_MANY, SIZE_ON_DISK);
            this.recentTestRuns =
                new LinkedTestRuns(user,
                    testRunDAO.getRecentFinishedTestRuns().stream()
                        .filter(testRun -> testRun.getOwner().equals(email))
                        .collect(Collectors.toList()))
                            .hide(OWNER, RESULTS, TEMPLATE_PARAMS, MUTATION_ACTIONS, RESTORE_ACTIONS,
                                RESOURCE_REQUIREMENTS, DELETE_MANY, SIZE_ON_DISK);
            this.totalSizeOnDisk = FileUtils.byteCountToDisplaySize(tests.stream()
                .mapToLong(Test::getSizeOnDiskBytes)
                .sum());
        }

        public boolean isOwner()
        {
            return user != null && user.getEmail().equals(email);
        }
    }

    public class TestRunView extends FalloutView
    {
        final List<Pair<TestCompletionNotification, Boolean>> allEmailNotify;
        final List<Pair<TestCompletionNotification, Boolean>> allSlackNotify;

        final LinkedTestRuns testRuns;
        final String name;
        final String email;
        final boolean deleted;
        Optional<String> templateParams;
        String errorMessage;

        public TestRunView(Optional<User> user, String email, Test test, List<TestRun> testRuns, boolean deleted)
        {
            super("testruns.mustache", user, mainView);
            this.email = email;

            this.testRuns = new LinkedTestRuns(user, testRuns)
                .hide(OWNER, TEST_NAME, RESTORE_ACTIONS, RESOURCE_REQUIREMENTS);
            this.name = test.getName();

            if (deleted)
            {
                this.testRuns.hide(MUTATION_ACTIONS);
            }

            try
            {
                String dereferencedDefinition = TestDefinition.getDereferencedYaml(test.getDefinition());
                this.templateParams = TestDefinition.splitDefaultsAndDefinition(dereferencedDefinition).getLeft();
            }
            catch (InvalidConfigurationException e)
            {
                this.templateParams = Optional.empty();
                errorMessage = e.getMessage();
            }
            this.deleted = deleted;

            allEmailNotify = Arrays.stream(TestCompletionNotification.values())
                .map(notify -> Pair.of(notify, user.map(user_ -> user_.getEmailPref() == notify).orElse(false)))
                .collect(Collectors.toList());
            allSlackNotify = Arrays.stream(TestCompletionNotification.values())
                .map(notify -> Pair.of(notify, user.map(user_ -> user_.getSlackPref() == notify).orElse(false)))
                .collect(Collectors.toList());
        }

        public boolean isOwner()
        {
            return user != null && user.getEmail().equals(email);
        }
    }

    public class TestAdminView extends FalloutView
    {
        final LinkedTestRuns runningTestRuns;
        final LinkedTestRuns queuedTestRuns;

        public TestAdminView(User user, QueuingTestRunner testRunner)
        {
            super("test-admin.mustache", user, mainView);

            runningTestRuns = new LinkedTestRuns(Optional.of(user), testRunner.getRunningTestRunsOrderedByDuration())
                .hide(FINISHED_AT, RESULTS, TEMPLATE_PARAMS, RESTORE_ACTIONS, DELETE_MANY, SIZE_ON_DISK);

            queuedTestRuns = new LinkedTestRuns(Optional.of(user), true, testRunner.getQueuedTestRuns())
                .hide(FINISHED_AT, RESULTS, TEMPLATE_PARAMS, RESTORE_ACTIONS, DELETE_MANY, SIZE_ON_DISK);
        }
    }

    public class TestDetailsView extends FalloutView
    {
        final String name;
        final TestRun testRun;
        public final LinkedTestRuns testRuns;
        final Map<String, Long> artifactsWithSize;
        final List<java.nio.file.Path> artifacts;
        final FalloutConfiguration configuration;
        private final URI baseUri;
        final boolean deleted;

        public class ArtifactInfo
        {
            final String name;
            final String url;
            final String liveUrl;
            final long sizeInBytes;
            final String displaySize;
            final boolean isDirectory;
            final List<ArtifactInfo> children;

            /** Create a directory entry */
            ArtifactInfo(String name, long sizeInBytes, List<ArtifactInfo> children)
            {
                this.name = name;
                this.url = null;
                this.liveUrl = null;
                this.sizeInBytes = sizeInBytes;
                this.displaySize = FileUtils.byteCountToDisplaySize(sizeInBytes);
                this.isDirectory = true;
                this.children = children;
            }

            /** Create a file entry */
            ArtifactInfo(String name, String url, String liveUrl, long sizeInBytes)
            {
                this.name = name;
                this.url = url;
                this.liveUrl = !testRun.getState().finished() && liveUrl.endsWith(".log") ? liveUrl : null;
                this.sizeInBytes = sizeInBytes;
                this.displaySize = FileUtils.byteCountToDisplaySize(sizeInBytes);
                this.isDirectory = false;
                this.children = List.of();
            }
        }

        public TestDetailsView(Optional<User> user, String name, TestRun run, FalloutConfiguration configuration)
        {
            super("testdetails.mustache", user, mainView);

            this.name = name;
            this.testRun = run;
            this.testRuns = new LinkedTestRuns(user, ImmutableList.of(testRun))
                .hide(OWNER, TEST_NAME, TEST_RUN, RESULTS, DEFINITION, ARTIFACTS_LINK, MUTATION_ACTIONS,
                    RESTORE_ACTIONS, DELETE_MANY);
            this.configuration = configuration;
            this.deleted = run instanceof DeletedTestRun;
            baseUri = uriFor(TestResource.class, "showTestRunArtifacts",
                testRun.getOwner(), testRun.getTestName(), testRun.getTestRunId());

            artifactsWithSize = testRun.getArtifacts();

            this.artifacts = artifactsWithSize.keySet().stream().map(p -> Paths.get(p)).collect(Collectors.toList());
            Collections.sort(this.artifacts, Comparator.comparing(java.nio.file.Path::toAbsolutePath));
        }

        public boolean hasArtifacts()
        {
            return !artifacts.isEmpty();
        }

        public String getName()
        {
            return name;
        }

        public TestRun getTestRun()
        {
            return testRun;
        }

        public String getStateAlertType()
        {
            return testRuns.testRuns.get(0).getStateAlertType();
        }

        public boolean failed()
        {
            return testRuns.testRuns.get(0).failed();
        }

        public boolean failedDuringWorkload()
        {
            return testRuns.testRuns.get(0).failedDuringWorkload();
        }

        private List<ArtifactInfo> getLevelFiles(java.nio.file.Path base)
        {
            List<ArtifactInfo> directories = new ArrayList<>();
            List<ArtifactInfo> files = new ArrayList<>();

            java.nio.file.Path last = null;
            for (java.nio.file.Path p : artifacts)
            {
                if (base == null || p.startsWith(base))
                {
                    Iterator<java.nio.file.Path> it = base == null ? p.iterator() : base.relativize(p).iterator();
                    java.nio.file.Path next = it.next();

                    //Listings are sorted
                    //Only process the same path part once
                    //since recursion will grab any children
                    if (last != null && next.equals(last))
                        continue;

                    boolean isDirectory = it.hasNext();
                    if (isDirectory)
                    {
                        directories.add(directoryTreeItem(base, next));
                    }
                    else
                    {
                        files.add(artifactTreeItem(p));
                    }

                    last = next;
                }
            }
            List<ArtifactInfo> res = new ArrayList<>();
            res.addAll(files);
            res.addAll(directories);
            return res;
        }

        private ArtifactInfo directoryTreeItem(java.nio.file.Path parentFolder, java.nio.file.Path dir)
        {
            java.nio.file.Path newParentFolder = parentFolder == null ? dir : Paths
                .get(parentFolder.toString(), dir.toString());
            List<ArtifactInfo> children = getLevelFiles(newParentFolder);

            long totalSizeBytes = children.stream().mapToLong(child -> child.sizeInBytes).sum();
            return new ArtifactInfo(dir.toString(), totalSizeBytes, children);
        }

        private ArtifactInfo artifactTreeItem(
            java.nio.file.Path pathInTestRunArtifacts)
        {
            java.nio.file.Path fullLocalPath =
                Artifacts.buildTestRunArtifactPath(configuration, testRun).resolve(pathInTestRunArtifacts);

            long fileSize = artifactsWithSize.get(pathInTestRunArtifacts.toString());

            // support artifacts that might have been gzipped since the test run
            if (Artifacts.hasStrippableGzSuffix(fullLocalPath.getFileName().toString()))
            {
                // If there's both an uncompressed and a compressed version of a file, don't
                // strip the suffix
                final String uncompressedPath = Artifacts.stripGzSuffix(pathInTestRunArtifacts.toString());
                final boolean uncompressedPathExists = artifactsWithSize.containsKey(uncompressedPath);

                // usually we transparently serve the gzipped content
                // but when the gz file is too large we link to it directly for download
                // so that the users browser does not run into trouble displaying huge log files
                final long size50megabyte = 50 * 1024 * 1024;
                final boolean huge = fileSize > size50megabyte;

                if (!huge && !uncompressedPathExists)
                {
                    pathInTestRunArtifacts = Paths.get(uncompressedPath);
                }
            }

            return new ArtifactInfo(
                pathInTestRunArtifacts.getFileName().toString(),
                baseUri.toString() + "/" + pathInTestRunArtifacts.toString(),
                UriBuilder
                    .fromUri(uriFor(TestResource.class, "showTestRunLiveArtifact",
                        testRun.getOwner(), testRun.getTestName(), testRun.getTestRunId(), ""))
                    .path("{artifactPath}")
                    .build(pathInTestRunArtifacts.toString())
                    .toString(),
                fileSize);
        }

        public List<ArtifactInfo> getFileTree()
        {
            return getLevelFiles(null);
        }
    }

    public class TrashBinView extends FalloutView
    {
        final LinkedTests tests;
        final LinkedTestRuns deletedTestRuns;

        public TrashBinView(Optional<User> user, List<DeletedTest> tests, List<DeletedTestRun> testRuns)
        {
            super("deleted-tests.mustache", user, mainView);
            this.tests = new LinkedTests(user, tests, createCanDeletePredicate());
            this.deletedTestRuns =
                new LinkedTestRuns(user, testRuns).hide(MUTATION_ACTIONS, TEMPLATE_PARAMS, DELETE_MANY, SIZE_ON_DISK);
        }
    }
}