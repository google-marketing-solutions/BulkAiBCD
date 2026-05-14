describe('Submit flow', () => {
  beforeEach(() => {
    cy.intercept('POST', '/api/v2/input/submit', {
      statusCode: 200,
      body: 'analysis-e2e-1',
    }).as('submit');
    cy.intercept('GET', '/api/v2/input/list/*', {
      statusCode: 200,
      body: [
        {
          analysisId: 'analysis-e2e-1',
          requesterId: 'default-user',
          analysisName: 'E2E Run',
          analysisType: 'standard',
          analysisStatus: 'PENDING',
        },
      ],
    }).as('list');
  });

  it('submits an analysis and navigates to the job list', () => {
    cy.visit('/new');
    cy.contains('Start a New Analysis');

    cy.get('input[matInput]').first().type('E2E Run');

    // Attach a video file via the hidden file input.
    cy.get('input[type="file"]').selectFile(
      {
        contents: Cypress.Buffer.from('fake video bytes'),
        fileName: 'video.mp4',
        mimeType: 'video/mp4',
      },
      {force: true},
    );

    cy.contains('button', /Run Analysis/i).click();

    cy.wait('@submit')
      .its('request.body')
      .should('include', {analysisName: 'E2E Run', analysisType: 'standard'});

    cy.url().should('include', '/list');
    cy.wait('@list');
    cy.contains('E2E Run');
  });
});
