package seen.kai.checker.controller;

import io.quarkus.qute.Template;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/")
public class IndexResource {

    @Inject
    Template index;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String index() {
        return index.render();
    }
}
