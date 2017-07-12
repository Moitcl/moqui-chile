package cl.moit.ws.bcentral;

import javax.jws.WebMethod;
import javax.jws.WebService;

@WebService
public interface SearchSeriesInterface {

    @WebMethod
    SearchSeriesResponse SearchSeries(SearchSeries searchSeries);

    @WebMethod GetSeriesResponse GetSeries(GetSeries getSeries);

}
