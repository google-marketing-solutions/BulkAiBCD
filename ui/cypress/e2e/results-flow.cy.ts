/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
