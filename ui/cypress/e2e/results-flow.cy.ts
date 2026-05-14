describe('Results flow', () => {
  beforeEach(() => {
    cy.intercept('GET', '/api/v2/output/videos/abc', {
      statusCode: 200,
      body: [
        {
          id: 'doc-1',
          analysisId: 'abc',
          videoId: 'v1',
          status: 'COMPLETED',
          brand: 'Acme',
          product: 'Widget',
          videoLanguage: 'en-US',
          vertical: 'Retail',
          assetName: 'Spot1',
        },
      ],
    }).as('videos');

    cy.intercept('GET', '/api/v2/output/report/abc', {
      statusCode: 200,
      body: 'VideoID,Brand\n"v1","Acme"\n',
      headers: {'content-type': 'text/csv'},
    }).as('report');
  });

  it('renders the breakdown table for an analysisId', () => {
    cy.visit('/list/results/abc');
    cy.wait('@videos');
    cy.contains('v1');
    cy.contains('Acme');
    cy.contains('Widget');
  });

  it('downloads the CSV report when the Full Report button is clicked', () => {
    cy.visit('/list/results/abc');
    cy.wait('@videos');
    cy.contains('button', /Full Report \(CSV\)/i).click();
    cy.wait('@report');
  });
});
